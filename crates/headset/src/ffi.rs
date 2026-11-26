use crate::field::{FieldReading, HeadsetField, HeadsetSetting, TouchSlot};

#[macro_export]
macro_rules! ffi_sorted_set {
    ($alias:ident, $item:ty) => {
        type $alias = ::std::collections::BTreeSet<$item>;
        ::uniffi::custom_type!($alias, ::std::vec::Vec<$item>, {
            remote,
            lower: |set| set.into_iter().collect(),
            try_lift: |items| Ok(items.into_iter().collect()),
        });
    };
}

#[macro_export]
macro_rules! ffi_sorted_map {
    ($alias:ident, $key:ty, $value:ty) => {
        type $alias = ::std::collections::BTreeMap<$key, $value>;
        ::uniffi::custom_type!($alias, ::std::collections::HashMap<$key, $value>, {
            remote,
            lower: |map| map.into_iter().collect(),
            try_lift: |entries| Ok(entries.into_iter().collect()),
        });
    };
}

ffi_sorted_set!(NumberSet, i32);
ffi_sorted_set!(TextSet, String);
ffi_sorted_set!(TouchSlotSet, TouchSlot);
ffi_sorted_set!(HeadsetFieldSet, HeadsetField);
ffi_sorted_set!(HeadsetSettingSet, HeadsetSetting);
ffi_sorted_map!(TouchSlotLabels, TouchSlot, String);
ffi_sorted_map!(HeadsetFieldReadings, HeadsetField, FieldReading);
ffi_sorted_map!(NoiseCycleReadings, String, Option<bool>);
ffi_sorted_map!(NoiseCycleFlags, String, bool);
