use crate::field::{FieldValue, HeadsetField};
use crate::model::BatteryLayout;

const NOT_PROVIDED: u8 = 0xff;
const IN_CASE_FLAG: u8 = 0x80;

enum Update {
    Level(i32),
    InCase(bool),
}

pub(super) fn initial_bytes(layout: BatteryLayout) -> Vec<u8> {
    match layout {
        BatteryLayout::SingleUnit => vec![NOT_PROVIDED, NOT_PROVIDED, 0x50],
        BatteryLayout::EarbudsOnly => vec![0x64, 0x32, NOT_PROVIDED],
        BatteryLayout::EarbudsAndCase => vec![0xe4, 0x32, 0x50],
    }
}

pub(super) fn changed(bytes: &[u8], field: HeadsetField, value: &FieldValue) -> Option<Vec<u8>> {
    let (index, update) = match (field, value) {
        (HeadsetField::LeftBattery, FieldValue::Number(level)) => (0, Update::Level(*level)),
        (HeadsetField::RightBattery, FieldValue::Number(level)) => (1, Update::Level(*level)),
        (HeadsetField::CaseBattery, FieldValue::Number(level)) => (2, Update::Level(*level)),
        (HeadsetField::LeftInCase, FieldValue::Toggle(inside)) => (0, Update::InCase(*inside)),
        (HeadsetField::RightInCase, FieldValue::Toggle(inside)) => (1, Update::InCase(*inside)),
        _ => return None,
    };
    let current = *bytes.get(index)?;
    if current == NOT_PROVIDED {
        return None;
    }
    let mut next = bytes.to_vec();
    next[index] = match update {
        Update::Level(level) => (1..=100)
            .contains(&level)
            .then_some((current & IN_CASE_FLAG) | level as u8)?,
        Update::InCase(true) => current | IN_CASE_FLAG,
        Update::InCase(false) => current & !IN_CASE_FLAG,
    };
    Some(next)
}
