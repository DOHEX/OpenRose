use crate::hex::{encode_hex, is_lower_hex};
use crate::model::{ModelProfile, ROSELINK_PREFIX_LENGTHS};
use std::collections::{BTreeMap, BTreeSet};
use std::sync::LazyLock;

const ROSE_NAME_PREFIX: &str = "rose";
const ROSESELSA_NAME_PREFIX: &str = "roseselsa";

pub struct ModelIdentification;

impl ModelIdentification {
    pub fn normalized_hex(manufacturer_data: &BTreeMap<u16, Vec<u8>>) -> String {
        let total_payload: usize = manufacturer_data.values().map(Vec::len).sum();
        let mut bytes = Vec::with_capacity(manufacturer_data.len() * 2 + total_payload);
        for &company_id in manufacturer_data.keys() {
            bytes.extend_from_slice(&company_id.to_le_bytes());
        }
        for payload in manufacturer_data.values() {
            bytes.extend_from_slice(payload);
        }
        encode_hex(&bytes)
    }

    pub fn identify(manufacturer_data: &BTreeMap<u16, Vec<u8>>) -> Option<ModelProfile> {
        let normalized = Self::normalized_hex(manufacturer_data);
        identify_from_table(&normalized, &ADVERTISEMENT_PREFIXES)
    }

    pub fn classic_address(
        normalized_manufacturer_data_hex: &str,
        profile: Option<ModelProfile>,
    ) -> Option<String> {
        let read = profile?.spec().advertised_classic_address?;
        read(normalized_manufacturer_data_hex)
    }

    pub fn validate_catalog() -> Result<(), String> {
        let mut seen = BTreeMap::<&'static str, ModelProfile>::new();
        for &profile in ModelProfile::ALL {
            let spec = profile.spec();
            if spec.advertisement_prefixes.is_empty() {
                return Err(format!(
                    "{} has no advertisement prefixes",
                    spec.display_name
                ));
            }
            for &prefix in spec.advertisement_prefixes {
                if !ROSELINK_PREFIX_LENGTHS.contains(&prefix.len()) || !is_lower_hex(prefix) {
                    return Err(format!(
                        "{} has invalid advertisement prefix: {prefix}",
                        spec.display_name
                    ));
                }
                if let Some(other) = seen.insert(prefix, profile) {
                    return Err(format!(
                        "duplicate advertisement prefix {prefix} for {other:?} and {profile:?}"
                    ));
                }
            }
            if spec.name_brand.trim().is_empty()
                || spec.name_brand != spec.name_brand.to_lowercase()
            {
                return Err(format!(
                    "{} name_brand must be lowercase",
                    spec.display_name
                ));
            }
            if spec.name_model_number.trim().is_empty()
                || spec.name_model_number != spec.name_model_number.to_lowercase()
            {
                return Err(format!(
                    "{} name_model_number must be lowercase",
                    spec.display_name
                ));
            }
        }
        Ok(())
    }
}
static ADVERTISEMENT_PREFIXES: LazyLock<BTreeMap<&'static str, ModelProfile>> =
    LazyLock::new(|| {
        let mut table = BTreeMap::new();
        for &profile in ModelProfile::ALL {
            for &prefix in profile.advertisement_prefixes() {
                let previous = table.insert(prefix, profile);
                assert!(
                    previous.is_none(),
                    "duplicate advertisement prefix {prefix}"
                );
            }
        }
        table
    });

fn identify_from_table<T: Copy>(normalized_hex: &str, table: &BTreeMap<&str, T>) -> Option<T> {
    for length in ROSELINK_PREFIX_LENGTHS {
        if let Some(prefix) = normalized_hex.get(..length)
            && let Some(&profile) = table.get(prefix)
        {
            return Some(profile);
        }
    }
    None
}

#[derive(Clone, Copy)]
struct NameEntry<T: Copy> {
    id: T,
    brand: &'static str,
    brand_aliases: &'static [&'static str],
    model_number: &'static str,
    requires_model: bool,
}

impl<T: Copy> NameEntry<T> {
    fn brands(&self) -> impl Iterator<Item = &'static str> {
        std::iter::once(self.brand).chain(self.brand_aliases.iter().copied())
    }

    fn has_brand(&self, words: &BTreeSet<String>) -> bool {
        self.brands().any(|b| words.contains(&compact(b)))
    }
}

fn compact(value: &str) -> String {
    value.chars().filter(|c| c.is_alphanumeric()).collect()
}

fn name_tokens(
    normalized: &str,
    compact_brands: &BTreeSet<String>,
    model_numbers: &BTreeSet<String>,
) -> BTreeSet<String> {
    let retail = normalized
        .strip_prefix(ROSESELSA_NAME_PREFIX)
        .unwrap_or(normalized);
    let retail = retail.strip_prefix(ROSE_NAME_PREFIX).unwrap_or(retail);
    let parts: Vec<&str> = retail
        .split(|c: char| !c.is_alphanumeric())
        .filter(|part| !part.is_empty())
        .collect();

    let mut words = BTreeSet::new();
    for start in 0..parts.len() {
        let mut joined = String::new();
        for end in start..parts.len() {
            joined.push_str(parts[end]);
            let next_starts_with_digit = parts
                .get(end + 1)
                .and_then(|next| next.chars().next())
                .is_some_and(|ch| ch.is_ascii_digit());
            if !next_starts_with_digit {
                words.insert(joined.clone());
            }
        }
    }

    let snapshot: Vec<String> = words.iter().cloned().collect();
    for word in &snapshot {
        for brand in compact_brands {
            if let Some(suffix) = word.strip_prefix(brand.as_str()) {
                if model_numbers.contains(suffix) || model_numbers.contains(word) {
                    words.insert(brand.clone());
                }
                if model_numbers.contains(suffix) {
                    words.insert(suffix.to_string());
                }
            }
        }
    }
    words
}

fn match_name_in_entries<T: Copy>(entries: &[NameEntry<T>], name: Option<&str>) -> Option<T> {
    let normalized = name?.trim().to_lowercase();
    if normalized.is_empty() {
        return None;
    }

    let compact_brands: BTreeSet<String> = entries
        .iter()
        .flat_map(|entry| entry.brands().map(compact))
        .collect();
    let model_numbers: BTreeSet<String> = entries
        .iter()
        .map(|entry| compact(entry.model_number))
        .collect();

    let words = name_tokens(&normalized, &compact_brands, &model_numbers);
    if !normalized.starts_with(ROSE_NAME_PREFIX)
        && !words.iter().any(|word| compact_brands.contains(word))
    {
        return None;
    }

    let numbered: Vec<NameEntry<T>> = entries
        .iter()
        .copied()
        .filter(|entry| {
            words.contains(&compact(entry.model_number))
                && (!entry.requires_model || entry.has_brand(&words))
        })
        .collect();

    if !numbered.is_empty() {
        let specific: Vec<NameEntry<T>> = numbered
            .iter()
            .copied()
            .filter(|entry| compact(entry.model_number) != compact(entry.brand))
            .collect();
        let pool = if specific.is_empty() {
            &numbered
        } else {
            &specific
        };
        let longest = pool
            .iter()
            .map(|entry| compact(entry.model_number).chars().count())
            .max()
            .unwrap_or(0);
        let candidates: Vec<NameEntry<T>> = pool
            .iter()
            .copied()
            .filter(|entry| compact(entry.model_number).chars().count() == longest)
            .collect();
        let branded: Vec<NameEntry<T>> = candidates
            .iter()
            .copied()
            .filter(|entry| entry.has_brand(&words))
            .collect();
        let winners = if branded.is_empty() {
            &candidates
        } else {
            &branded
        };
        return if winners.len() == 1 {
            Some(winners[0].id)
        } else {
            None
        };
    }

    let has_digit = normalized.chars().any(|ch| ch.is_ascii_digit());
    let branded: Vec<NameEntry<T>> = entries
        .iter()
        .copied()
        .filter(|entry| !has_digit && !entry.requires_model && entry.has_brand(&words))
        .collect();
    if branded.len() == 1 {
        Some(branded[0].id)
    } else {
        None
    }
}

fn catalog_name_entries() -> Vec<NameEntry<ModelProfile>> {
    ModelProfile::ALL
        .iter()
        .copied()
        .map(|profile| {
            let spec = profile.spec();
            NameEntry {
                id: profile,
                brand: spec.name_brand,
                brand_aliases: spec.name_brand_aliases,
                model_number: spec.name_model_number,
                requires_model: spec.name_requires_model,
            }
        })
        .collect()
}

pub(crate) fn match_profile_name(name: Option<&str>) -> Option<ModelProfile> {
    let entries = catalog_name_entries();
    match_name_in_entries(&entries, name)
}

pub(crate) fn wake_name_pattern() -> String {
    let entries = catalog_name_entries();
    let compact_brands: BTreeSet<String> = entries
        .iter()
        .flat_map(|entry| entry.brands().map(compact))
        .collect();
    let model_numbers: BTreeSet<String> = entries
        .iter()
        .map(|entry| compact(entry.model_number))
        .collect();
    let mut retail = compact_brands.clone();
    for number in &model_numbers {
        if compact_brands
            .iter()
            .any(|brand| number.starts_with(brand.as_str()))
        {
            retail.insert(number.clone());
        }
    }
    let boundary = "[\\p{L}\\p{N}]";
    let brand = pattern_alternatives(&retail);
    let model = pattern_alternatives(&model_numbers);
    format!("(?is){ROSE_NAME_PREFIX}.*|.*(?<!{boundary})(?:{brand})(?:{model})?(?!{boundary}).*")
}

fn pattern_alternatives(words: &BTreeSet<String>) -> String {
    words
        .iter()
        .map(|word| {
            word.chars()
                .map(escape_regex_char)
                .collect::<Vec<_>>()
                .join("[\\s_-]*")
        })
        .collect::<Vec<_>>()
        .join("|")
}

fn escape_regex_char(ch: char) -> String {
    if matches!(
        ch,
        '\\' | '^' | '$' | '.' | '|' | '?' | '*' | '+' | '(' | ')' | '[' | ']' | '{' | '}'
    ) {
        format!("\\{ch}")
    } else {
        ch.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::{NameEntry, identify_from_table, match_name_in_entries};
    use std::collections::BTreeMap;

    #[test]
    fn longest_advertisement_prefix_wins_in_12_8_6_order() {
        let mut table = BTreeMap::new();
        table.insert("aabbcc", "six");
        table.insert("aabbccdd", "eight");
        table.insert("aabbccddeeff", "twelve");

        assert_eq!(
            Some("twelve"),
            identify_from_table("aabbccddeeff99", &table)
        );
        assert_eq!(Some("eight"), identify_from_table("aabbccdd001199", &table));
        assert_eq!(Some("six"), identify_from_table("aabbcc00112299", &table));
        assert_eq!(None, identify_from_table("aabb00", &table));
    }

    #[test]
    fn name_matching_keeps_generations_ambiguity_and_compact_brands_distinct() {
        let entries = [
            NameEntry {
                id: "i5",
                brand: "earfree",
                brand_aliases: &[],
                model_number: "i5",
                requires_model: false,
            },
            NameEntry {
                id: "i7",
                brand: "earfeel",
                brand_aliases: &[],
                model_number: "i7",
                requires_model: false,
            },
            NameEntry {
                id: "swift",
                brand: "swift",
                brand_aliases: &[],
                model_number: "swift",
                requires_model: false,
            },
            NameEntry {
                id: "swift2",
                brand: "swift",
                brand_aliases: &[],
                model_number: "swift 2",
                requires_model: true,
            },
            NameEntry {
                id: "budsfeel_mk2",
                brand: "budsfeel",
                brand_aliases: &[],
                model_number: "mk2",
                requires_model: true,
            },
            NameEntry {
                id: "openfeel_mk2",
                brand: "openfeel",
                brand_aliases: &[],
                model_number: "mk2",
                requires_model: true,
            },
            NameEntry {
                id: "ceramics_x",
                brand: "ceramics",
                brand_aliases: &[],
                model_number: "x",
                requires_model: true,
            },
            NameEntry {
                id: "ceramics_x_7034",
                brand: "ceramics",
                brand_aliases: &[],
                model_number: "x",
                requires_model: true,
            },
        ];

        assert_eq!(
            Some("i5"),
            match_name_in_entries(&entries, Some("EarFeeli5"))
        );
        assert_eq!(
            Some("i5"),
            match_name_in_entries(&entries, Some("ROSESELSAEarFeeli5"))
        );
        assert_eq!(
            Some("i7"),
            match_name_in_entries(&entries, Some("EarFree i7"))
        );
        assert_eq!(Some("i7"), match_name_in_entries(&entries, Some("EarFeel")));
        assert_eq!(
            Some("swift"),
            match_name_in_entries(&entries, Some("ROSE Swift"))
        );
        assert_eq!(
            Some("swift2"),
            match_name_in_entries(&entries, Some("ROSE Swift 2"))
        );
        assert_eq!(
            Some("swift2"),
            match_name_in_entries(&entries, Some("SWIFT2"))
        );
        assert_eq!(None, match_name_in_entries(&entries, Some("ROSE Swift 20")));
        assert_eq!(
            Some("budsfeel_mk2"),
            match_name_in_entries(&entries, Some("BudsFeel MK2"))
        );
        assert_eq!(
            Some("openfeel_mk2"),
            match_name_in_entries(&entries, Some("ROSE OpenFeel-MK2"))
        );
        assert_eq!(None, match_name_in_entries(&entries, Some("ROSE MK2")));
        assert_eq!(None, match_name_in_entries(&entries, Some("CERAMICS-X")));
    }
}
