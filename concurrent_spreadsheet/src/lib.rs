mod chained_dep_worker;
mod single_dep_worker;
mod variables;

use crate::variables::{build_env, is_valid_key};
use log::info;
use rsheet_lib::cell_expr::{CellExpr, CellExprEvalError};
use rsheet_lib::cell_value::CellValue;
use rsheet_lib::cells::column_number_to_name;
use rsheet_lib::command::{CellIdentifier, Command};
use rsheet_lib::connect::{
    Connection, Manager, ReadMessageResult, Reader, WriteMessageResult, Writer,
};
use rsheet_lib::replies::Reply;
use std::collections::HashMap;
use std::error::Error;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;

static CELL_VERSION_COUNTER: AtomicU64 = AtomicU64::new(1);

#[derive(Clone)]
pub struct CellData {
    expr: Option<String>,
    version: u64, // increasing version number.
    value: CellValue,
}

/// A simple spreadsheet mapping cell identifiers to their values.
struct Spreadsheet {
    cells: HashMap<CellIdentifier, CellData>,
}

impl Spreadsheet {
    fn new() -> Self {
        Self {
            cells: HashMap::new(),
        }
    }

    fn get(&self, cell: &CellIdentifier) -> CellValue {
        self.cells
            .get(cell)
            .map(|d| d.value.clone())
            .unwrap_or(CellValue::None)
    }

    fn set(&mut self, cell: CellIdentifier, expr: String, value: CellValue) {
        let version = CELL_VERSION_COUNTER.fetch_add(1, Ordering::SeqCst);
        self.cells.insert(
            cell,
            CellData {
                expr: Some(expr),
                version,
                value,
            },
        );
    }
}

/// Handles one connection in its own thread.
fn handle_connection<R, W>(mut reader: R, mut writer: W, spreadsheet: Arc<Mutex<Spreadsheet>>)
where
    R: Reader,
    W: Writer,
{
    let format_cell = |cell_id: &CellIdentifier| -> String {
        let mut cell_str = column_number_to_name(cell_id.col);
        cell_str.push_str(&(cell_id.row + 1).to_string());
        cell_str
    };
    loop {
        info!("Connection thread: got message");
        match reader.read_message() {
            ReadMessageResult::Message(msg) => {
                let reply_option: Option<Reply> = match msg.parse::<Command>() {
                    Ok(command) => match command {
                        Command::Get { cell_identifier } => {
                            // Build a display cell name (row displayed 1-indexed).
                            let cell_str = format_cell(&cell_identifier);
                            if !is_valid_key(&cell_str) {
                                Some(Reply::Error("Invalid Key Provided".to_string()))
                            } else {
                                let value = {
                                    let sheet = spreadsheet.lock().unwrap();
                                    sheet.get(&cell_identifier)
                                };
                                match value {
                                    CellValue::Error(ref err)
                                        if err == "Cell depends on an error" =>
                                    {
                                        Some(Reply::Error(err.clone()))
                                    }
                                    _ => Some(Reply::Value(cell_str, value)),
                                }
                            }
                        }
                        Command::Set {
                            cell_identifier,
                            cell_expr,
                        } => {
                            let cell_str = format_cell(&cell_identifier);
                            if !is_valid_key(&cell_str) {
                                Some(Reply::Error("Invalid Key Provided".to_string()))
                            } else {
                                let var_names =
                                    CellExpr::find_variable_names(&CellExpr::new(&cell_expr));
                                let env = build_env(&spreadsheet, var_names);

                                // Below is how I differentiate between a cell where the expression is invalid,
                                // a dependency cell that is empty,and a cell that depends on an error.
                                let eval_res = CellExpr::evaluate(CellExpr::new(&cell_expr), &env);
                                let evaluated = match eval_res {
                                    Ok(CellValue::Error(e)) => CellValue::Error(e),
                                    Ok(value) => value,
                                    Err(CellExprEvalError::VariableDependsOnError) => {
                                        CellValue::Error("Cell depends on an error".to_string())
                                    }
                                };
                                {
                                    let mut sheet = spreadsheet.lock().unwrap();
                                    sheet.set(
                                        cell_identifier.clone(),
                                        cell_expr.clone(),
                                        evaluated,
                                    );
                                }
                                // Set commands produce no output.
                                None
                            }
                        }
                    },
                    Err(e) => Some(Reply::Error(e)),
                };

                // Send the reply back to the client if one was generated.
                if let Some(reply) = reply_option {
                    match writer.write_message(reply) {
                        WriteMessageResult::Ok => {}
                        WriteMessageResult::ConnectionClosed => break,
                        WriteMessageResult::Err(e) => {
                            eprintln!("Error writing message: {:?}", e);
                            break;
                        }
                    }
                }
            }
            ReadMessageResult::ConnectionClosed => break,
            ReadMessageResult::Err(e) => {
                eprintln!("Error reading message: {:?}", e);
                break;
            }
        }
    }
}

/// Accepts new connections and spawns a new thread for each connection.
pub fn start_server<M>(mut manager: M) -> Result<(), Box<dyn Error>>
where
    M: Manager,
{
    // Shared spreadsheet among all connections.
    let spreadsheet = Arc::new(Mutex::new(Spreadsheet::new()));
    let mut handles = vec![];

    // Start the dependency worker thread.
    // This thread will periodically check for changes in the spreadsheet
    // and update the cells accordingly.
    {
        let sheet_clone = spreadsheet.clone();
        thread::spawn(move || {
            chained_dep_worker::dependency_worker(sheet_clone);
        });
    }

    // Accept new connections in a loop.
    loop {
        match manager.accept_new_connection() {
            Connection::NewConnection { reader, writer } => {
                let sheet_clone = spreadsheet.clone();
                let handle = thread::spawn(move || handle_connection(reader, writer, sheet_clone));
                handles.push(handle);
            }
            Connection::NoMoreConnections => break,
        }
    }

    // Wait for all connection threads to finish.
    for handle in handles {
        handle.join().unwrap();
    }
    Ok(())
}
