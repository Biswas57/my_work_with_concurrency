use crate::{CellData, CELL_VERSION_COUNTER};
use std::collections::HashMap;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::thread;

use std::time::Duration;

use rsheet_lib::cell_expr::{CellArgument, CellExpr};
use rsheet_lib::cell_value::CellValue;
use rsheet_lib::command::CellIdentifier;

use crate::Spreadsheet;

// helper function extracts the values over a range and computes the maximum version
pub fn version_collection(
    cells: &HashMap<CellIdentifier, CellData>,
    start: CellIdentifier,
    end: CellIdentifier,
) -> (CellArgument, u64) {
    // collect values and track maximum version.
    fn collect_max_version<I>(
        iter: I,
        retrieve_val: impl Fn(&CellIdentifier) -> (CellValue, u64),
    ) -> (Vec<CellValue>, u64)
    where
        I: Iterator<Item = CellIdentifier>,
    {
        let mut values = Vec::new();
        let mut max_ver = 0;
        for cell in iter {
            let (val, ver) = retrieve_val(&cell);
            values.push(val);
            max_ver = max_ver.max(ver);
        }
        (values, max_ver)
    }

    let retrieve_val = |cell_id: &CellIdentifier| {
        cells
            .get(cell_id)
            .map(|data| (data.value.clone(), data.version))
            .unwrap_or((CellValue::None, 0))
    };

    if start.row == end.row {
        // Horizontal vector.
        let iter = (start.col..=end.col).map(|col| CellIdentifier {
            col,
            row: start.row,
        });
        let (values, max_ver) = collect_max_version(iter, retrieve_val);
        (CellArgument::Vector(values), max_ver)
    } else if start.col == end.col {
        // Vertical vector.
        let iter = (start.row..=end.row).map(|row| CellIdentifier {
            col: start.col,
            row,
        });
        let (values, max_ver) = collect_max_version(iter, retrieve_val);
        (CellArgument::Vector(values), max_ver)
    } else {
        // Matrix variable.
        let mut matrix = Vec::new();
        let mut overall_max = 0;
        for row in start.row..=end.row {
            let iter = (start.col..=end.col).map(|col| CellIdentifier { col, row });
            let (vals, max_ver) = collect_max_version(iter, retrieve_val);
            matrix.push(vals);
            overall_max = overall_max.max(max_ver);
        }
        (CellArgument::Matrix(matrix), overall_max)
    }
}

#[allow(dead_code)]
// Worker thread that evaluates expressions and updates the spreadsheet.
// Don't use it anymore because it doesn't handle chained dependencies.
// but still wanted to keep it just to show progression.
pub fn dependency_worker(spreadsheet: Arc<Mutex<Spreadsheet>>) {
    loop {
        thread::sleep(Duration::from_millis(50));
        // Prepare updates (we build a list under lock then release it)
        let updates: Vec<(CellIdentifier, CellValue, HashMap<String, u64>)> = {
            let sheet = spreadsheet.lock().unwrap();
            let mut upd_vec: Vec<(CellIdentifier, CellValue, HashMap<String, u64>)> = Vec::new();
            for (cell_id, data) in sheet.cells.iter() {
                if let Some(expr_str) = &data.expr {
                    let expr = CellExpr::new(expr_str);
                    let var_names = CellExpr::find_variable_names(&expr);
                    if var_names.is_empty() {
                        continue;
                    }
                    let mut env = HashMap::new();
                    let mut dep_versions = HashMap::new();
                    // For now, i'm using dependencies as scalars and ranges
                    for var in var_names {
                        if var.contains('_') {
                            // Handle range (vector or matrix) dependency.
                            let parts: Vec<&str> = var.split('_').collect();
                            if let (Some(start), Some(end)) = (
                                parts[0].parse::<CellIdentifier>().ok(),
                                parts[1].parse::<CellIdentifier>().ok(),
                            ) {
                                let (arg, max_ver) = version_collection(&sheet.cells, start, end);
                                env.insert(var.clone(), arg);
                                dep_versions.insert(var, max_ver);
                            }
                        } else {
                            // Scalar variable.
                            if let Some(dep_id) = var.parse::<CellIdentifier>().ok() {
                                let (val, ver) = sheet
                                    .cells
                                    .get(&dep_id)
                                    .map(|data| (data.value.clone(), data.version))
                                    .unwrap_or((CellValue::None, 0));
                                env.insert(var.clone(), CellArgument::Value(val));
                                dep_versions.insert(var, ver);
                            }
                        }
                    }
                    let new_val = match CellExpr::evaluate(expr, &env) {
                        Ok(val) => val,
                        Err(_) => CellValue::Error(
                            "Error evaluating expression in worker thread".to_string(),
                        ),
                    };
                    upd_vec.push((cell_id.clone(), new_val, dep_versions));
                }
            }
            upd_vec
        };

        // Now try to apply updates.
        if !updates.is_empty() {
            let mut sheet = spreadsheet.lock().unwrap();
            for (cell_id, new_val, dep_versions) in updates {
                // Re-check dependency versions.
                let mut valid = true;
                if let Some(cell_data) = sheet.cells.get(&cell_id) {
                    if let Some(expr_str) = &cell_data.expr {
                        let expr = CellExpr::new(expr_str);
                        let var_names = CellExpr::find_variable_names(&expr);
                        for var in var_names {
                            if var.contains('_') {
                                let parts: Vec<&str> = var.split('_').collect();
                                if parts.len() != 2 {
                                    valid = false;
                                    break;
                                }
                                if let (Some(start), Some(end)) = (
                                    parts[0].parse::<CellIdentifier>().ok(),
                                    parts[1].parse::<CellIdentifier>().ok(),
                                ) {
                                    let (_, computed_ver) =
                                        version_collection(&sheet.cells, start, end);
                                    if let Some(&old_ver) = dep_versions.get(&var) {
                                        if computed_ver != old_ver {
                                            valid = false;
                                            break;
                                        }
                                    }
                                } else {
                                    valid = false;
                                    break;
                                }
                            } else {
                                if let Ok(dep_id) = var.parse::<CellIdentifier>() {
                                    let current_ver =
                                        sheet.cells.get(&dep_id).map(|d| d.version).unwrap_or(0);
                                    if let Some(&old_ver) = dep_versions.get(&var) {
                                        if current_ver != old_ver {
                                            valid = false;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if valid {
                    let new_version = CELL_VERSION_COUNTER.fetch_add(1, Ordering::SeqCst);
                    if let Some(cell_data) = sheet.cells.get_mut(&cell_id) {
                        cell_data.value = new_val;
                        cell_data.version = new_version;
                    }
                }
            }
        }
    }
}
