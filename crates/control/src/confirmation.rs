use crate::transport::TransportError;
use openrose_headset::{
    FieldReading, FieldValue, HeadsetField, ProtocolQuery, ProtocolReply, confirmation_value,
};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Default)]
pub(crate) struct ReadProgress {
    pub(crate) keys: BTreeSet<String>,
    pub(crate) meaningful: bool,
    pub(crate) ended: bool,
    pub(crate) key_revisions: BTreeMap<String, u64>,
    pub(crate) field_revisions: BTreeMap<HeadsetField, u64>,
    pub(crate) failure: Option<TransportError>,
}

impl ReadProgress {
    pub(crate) fn received(&mut self, replies: &[ProtocolReply]) {
        let keys: BTreeSet<&String> = replies.iter().flat_map(|r| &r.response_keys).collect();
        let fields: BTreeSet<HeadsetField> =
            replies.iter().flat_map(|r| r.readings.keys().copied()).collect();
        for key in keys {
            self.keys.insert(key.clone());
            *self.key_revisions.entry(key.clone()).or_default() += 1;
        }
        for field in fields {
            *self.field_revisions.entry(field).or_default() += 1;
        }
        self.meaningful = self.meaningful || replies.iter().any(|reply| reply.accepted);
    }

    fn key_revision(&self, key: &str) -> u64 {
        self.key_revisions.get(key).copied().unwrap_or(0)
    }

    fn field_revision(&self, field: HeadsetField) -> u64 {
        self.field_revisions.get(&field).copied().unwrap_or(0)
    }
}

pub(crate) struct SettingConfirmation {
    expected: BTreeMap<HeadsetField, FieldValue>,
    field_baselines: BTreeMap<HeadsetField, u64>,
    actual: BTreeMap<HeadsetField, FieldValue>,
}

impl SettingConfirmation {
    pub(crate) fn new(expected: BTreeMap<HeadsetField, FieldValue>) -> Self {
        Self {
            expected,
            field_baselines: BTreeMap::new(),
            actual: BTreeMap::new(),
        }
    }

    pub(crate) fn applied(&self) -> bool {
        self.expected
            .iter()
            .all(|(field, value)| self.actual.get(field) == Some(value))
    }

    pub(crate) fn confirmed_fields(&self) -> BTreeSet<HeadsetField> {
        self.actual
            .iter()
            .filter(|(field, value)| self.expected.get(field) == Some(value))
            .map(|(field, _)| *field)
            .collect()
    }

    pub(crate) fn begin_query(&mut self, query: &ProtocolQuery, baseline: &ReadProgress) {
        for field in &query.expected_fields {
            if self.expected.contains_key(field) {
                self.field_baselines
                    .insert(*field, baseline.field_revision(*field));
            }
        }
    }

    pub(crate) fn observe(
        &mut self,
        query: &ProtocolQuery,
        baseline: &ReadProgress,
        progress: &ReadProgress,
        fields: &BTreeMap<HeadsetField, FieldReading>,
    ) -> bool {
        let received = query
            .response_keys
            .iter()
            .all(|key| progress.key_revision(key) > baseline.key_revision(key));
        self.actual = self
            .field_baselines
            .iter()
            .filter_map(|(&field, &revision)| {
                if !received && query.expected_fields.contains(&field) {
                    return None;
                }
                if progress.field_revision(field) <= revision {
                    return None;
                }
                let expected = self.expected.get(&field)?;
                confirmation_value(fields.get(&field), expected).map(|value| (field, value))
            })
            .collect();
        received && self.actual.len() == self.field_baselines.len()
    }
}
