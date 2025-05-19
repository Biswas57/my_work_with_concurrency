use rsheet_lib::cell_expr::CellArgument;
use rsheet_lib::cell_value::CellValue;
use rsheet_lib::command::CellIdentifier;
use std::collections::HashMap;

// Check if a key is valid according to the rules:
pub fn is_valid_key(key: &str) -> bool {
    if key.is_empty() {
        return false;
    }
    let mut has_letter = false;
    let mut has_digit = false;
    let mut digits_started = false;

    for ch in key.chars() {
        if ch.is_ascii_alphabetic() && !digits_started {
            if !ch.is_uppercase() {
                return false;
            }
            has_letter = true;
        } else if ch.is_ascii_digit() {
            has_digit = true;
            digits_started = true;
        } else {
            return false;
        }
    }
    has_letter && has_digit
}

/// Converts a CellValue to a CellArgument.
pub fn value_to_argument(val: &CellValue) -> CellArgument {
    CellArgument::Value(val.clone())
}

// Helper to collect a range of cells as a matrix.
fn collect_range(
    start: CellIdentifier,
    end: CellIdentifier,
    retrieve_value: &impl Fn(&CellIdentifier) -> CellValue,
) -> Vec<Vec<CellValue>> {
    let mut matrix = Vec::new();
    for row in start.row..=end.row {
        let mut row_vec = Vec::new();
        for col in start.col..=end.col {
            let cell = CellIdentifier { col, row };
            row_vec.push(retrieve_value(&cell));
        }
        matrix.push(row_vec);
    }
    matrix
}

/// Build the evaluation environment for an expression.
/// For each variable name (as returned by CellExpr::find_variable_names),
/// look up its corresponding value from the spreadsheet, and create a mapping
/// from variable name to a CellArgument. Supports scalar variables (e.g. "A1"),
/// vector variables (e.g. "A1_A3"), and matrix variables (e.g. "A1_B3").
pub fn build_env(
    spreadsheet: &std::sync::Arc<std::sync::Mutex<super::Spreadsheet>>,
    var_names: Vec<String>,
) -> HashMap<String, CellArgument> {
    let mut env = HashMap::new();
    let sheet = spreadsheet.lock().unwrap();

    let retrieve_value = |cell_id: &CellIdentifier| {
        sheet
            .cells
            .get(cell_id)
            .map(|data| data.value.clone())
            .unwrap_or(CellValue::None)
    };

    for var in var_names {
        if let Some(idx) = var.find('_') {
            let start_str = &var[..idx];
            let end_str = &var[idx + 1..];
            if let (Some(start), Some(end)) = (
                start_str.parse::<CellIdentifier>().ok(),
                end_str.parse::<CellIdentifier>().ok(),
            ) {
                let matrix = collect_range(start, end, &retrieve_value);
                if start.row == end.row {
                    // Horizontal vector: use the first (and only) row.
                    if let Some(first_row) = matrix.first() {
                        env.insert(var.clone(), CellArgument::Vector(first_row.clone()));
                    }
                } else if start.col == end.col {
                    // Vertical vector: each row is a single value.
                    let vector: Vec<CellValue> = matrix
                        .into_iter()
                        .filter_map(|row| row.into_iter().next())
                        .collect();
                    env.insert(var.clone(), CellArgument::Vector(vector));
                } else {
                    // Matrix variable.
                    env.insert(var.clone(), CellArgument::Matrix(matrix));
                }
            }
        } else {
            if let Some(cell_id) = var.parse::<CellIdentifier>().ok() {
                let val = retrieve_value(&cell_id);
                env.insert(var.clone(), value_to_argument(&val));
            }
        }
    }
    env
}

/// Parse a cell identifier string (for example "A1") into a CellIdentifier.
/// This assumes that the column letters come first (in uppercase) followed by
/// a row number (1-indexed). The returned CellIdentifier is 0-indexed.
///
/// Note: This function isn't needed coz i realised the CellIdentifier struct
/// already implements FromStr trait.
#[allow(dead_code)]
fn parse_cell_identifier(s: &str) -> Option<CellIdentifier> {
    let mut chars = s.chars();
    let mut col = 0u32;
    let mut letter_count = 0;
    while let Some(c) = chars.next() {
        if c.is_ascii_uppercase() {
            letter_count += 1;
            col = col * 26 + ((c as u8 - b'A' + 1) as u32);
        } else {
            if letter_count == 0 {
                return None;
            }
            let digits: String = std::iter::once(c).chain(chars).collect();
            if let Ok(row_num) = digits.parse::<u32>() {
                return Some(CellIdentifier {
                    col: col - 1,
                    row: row_num - 1,
                });
            } else {
                return None;
            }
        }
    }
    None
}
