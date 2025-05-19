use crate::single_dep_worker::version_collection;
use crate::{CellData, CELL_VERSION_COUNTER};
use std::collections::{HashMap, VecDeque};
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use rsheet_lib::cell_expr::{CellArgument, CellExpr, CellExprEvalError};
use rsheet_lib::cell_value::CellValue;
use rsheet_lib::command::CellIdentifier;

use crate::Spreadsheet;

/// Build the dependency graph as a mapping from a cell (dependency) to a list of cells that depend on it.
fn build_dependency_graph(
    cells: &HashMap<CellIdentifier, CellData>,
) -> HashMap<CellIdentifier, Vec<CellIdentifier>> {
    let mut graph: HashMap<CellIdentifier, Vec<CellIdentifier>> = HashMap::new();
    // Iterate over each cell that has an expression.
    for (cell_id, data) in cells.iter() {
        if let Some(expr_str) = &data.expr {
            let expr = CellExpr::new(expr_str);
            let var_names = CellExpr::find_variable_names(&expr);
            for var in var_names {
                // For scalar dependencies.
                if !var.contains('_') {
                    if let Ok(dep_id) = var.parse::<CellIdentifier>() {
                        graph.entry(dep_id).or_default().push(cell_id.clone());
                    }
                } else {
                    // For range dependencies, add an edge for every cell in the range.
                    let parts: Vec<&str> = var.split('_').collect();
                    if let (Some(start), Some(end)) = (
                        parts[0].parse::<CellIdentifier>().ok(),
                        parts[1].parse::<CellIdentifier>().ok(),
                    ) {
                        if start.row == end.row {
                            for col in start.col..=end.col {
                                let dep_id = CellIdentifier {
                                    col,
                                    row: start.row,
                                };
                                graph.entry(dep_id).or_default().push(cell_id.clone());
                            }
                        } else if start.col == end.col {
                            for row in start.row..=end.row {
                                let dep_id = CellIdentifier {
                                    col: start.col,
                                    row,
                                };
                                graph.entry(dep_id).or_default().push(cell_id.clone());
                            }
                        } else {
                            // For matrix ranges, add each cell in the rectangle.
                            for row in start.row..=end.row {
                                for col in start.col..=end.col {
                                    let dep_id: CellIdentifier = CellIdentifier { col, row };
                                    graph.entry(dep_id).or_default().push(cell_id.clone());
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    graph
}

/// Compute the in-degree of each cell that appears as a node in the dependency graph.
fn compute_indegree(
    graph: &HashMap<CellIdentifier, Vec<CellIdentifier>>,
) -> HashMap<CellIdentifier, usize> {
    let mut indegree = HashMap::new();
    for dependencies in graph.values() {
        for dep in dependencies {
            *indegree.entry(dep.clone()).or_insert(0) += 1;
        }
    }
    indegree
}

fn propagate_updates(
    spreadsheet: &Arc<Mutex<Spreadsheet>>,
    graph: &HashMap<CellIdentifier, Vec<CellIdentifier>>,
) {
    let snapshot = {
        let sheet = spreadsheet.lock().unwrap();
        sheet.cells.clone()
    };

    // build indegree & initial queue as before…
    let mut indegree = compute_indegree(graph);
    for (cell_id, data) in snapshot.iter() {
        if data.expr.is_some() && !indegree.contains_key(cell_id) {
            indegree.insert(cell_id.clone(), 0);
        }
    }
    let mut queue: VecDeque<_> = indegree
        .iter()
        .filter_map(|(node, &deg)| if deg == 0 { Some(node.clone()) } else { None })
        .collect();

    while let Some(cell_id) = queue.pop_front() {
        // grab the original version from our frozen snapshot
        let orig_version = snapshot.get(&cell_id).unwrap().version;

        if let Some(cell_data) = snapshot.get(&cell_id).and_then(|d| d.expr.as_ref()) {
            let expr = CellExpr::new(cell_data);
            let var_names = CellExpr::find_variable_names(&expr);
            // build env from snapshot…
            let env = {
                let mut env = HashMap::new();
                for var in var_names {
                    if !var.contains('_') {
                        if let Ok(dep_id) = var.parse::<CellIdentifier>() {
                            let value = snapshot
                                .get(&dep_id)
                                .map(|d| d.value.clone())
                                .unwrap_or(CellValue::None);
                            env.insert(var.clone(), CellArgument::Value(value));
                        }
                    } else {
                        let parts: Vec<&str> = var.split('_').collect();
                        if let (Some(start), Some(end)) = (
                            parts[0].parse::<CellIdentifier>().ok(),
                            parts[1].parse::<CellIdentifier>().ok(),
                        ) {
                            let (arg, _) = version_collection(&snapshot, start, end);
                            env.insert(var.clone(), arg);
                        }
                    }
                }
                env
            };

            let new_val = match CellExpr::evaluate(expr, &env) {
                Ok(CellValue::Error(e)) => CellValue::Error(e),
                Ok(v) => v,
                Err(CellExprEvalError::VariableDependsOnError) => {
                    CellValue::Error("Cell depends on an error".into())
                }
            };

            // only overwrite if nobody has bumped this cell since we started
            let mut sheet = spreadsheet.lock().unwrap();
            if let Some(cell_data_mut) = sheet.cells.get_mut(&cell_id) {
                if cell_data_mut.version == orig_version && new_val != cell_data_mut.value {
                    cell_data_mut.value = new_val;
                    cell_data_mut.version = CELL_VERSION_COUNTER.fetch_add(1, Ordering::SeqCst);
                }
            }
        }

        // reduce indegree of dependents exactly as before…
        if let Some(deps) = graph.get(&cell_id) {
            for dep in deps {
                if let Some(deg) = indegree.get_mut(dep) {
                    *deg -= 1;
                    if *deg == 0 {
                        queue.push_back(dep.clone());
                    }
                }
            }
        }
    }
}

/// The chained dependency worker builds the dependency graph and uses topological sort to cascade updates.
pub fn dependency_worker(spreadsheet: Arc<Mutex<Spreadsheet>>) {
    loop {
        thread::sleep(Duration::from_millis(50));
        {
            // Acquire lock and build a snapshot and dependency graph.
            let sheet = spreadsheet.lock().unwrap();
            let snapshot = sheet.cells.clone();
            let dep_graph = build_dependency_graph(&snapshot);

            // this basically unlocks the spreadsheet and allows other threads to work on it
            drop(sheet);
            propagate_updates(&spreadsheet, &dep_graph);
        }
    }
}
