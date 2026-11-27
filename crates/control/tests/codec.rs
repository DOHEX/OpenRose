use openrose_control::codec::{CodecError, ControlCommand, WIRE_FORMAT_VERSION};
use openrose_control::snapshot::SessionReference;
use openrose_headset::{HeadsetCommand, HeadsetId, NoiseMode};
use std::collections::BTreeMap;

#[test]
fn all_control_commands_survive_the_versioned_byte_round_trip() {
    let headset = HeadsetId::new("AA:BB:CC:DD:EE:01");
    let reference = SessionReference {
        headset_id: headset.clone(),
        session_id: 42,
    };
    let mut cycle = BTreeMap::new();
    cycle.insert("anc".to_string(), true);
    cycle.insert("normal".to_string(), true);
    cycle.insert("transparent".to_string(), false);

    let commands = [
        ControlCommand::RefreshBondedHeadsets,
        ControlCommand::StartScan,
        ControlCommand::StopScan,
        ControlCommand::SelectCurrentHeadset {
            headset_id: headset.clone(),
        },
        ControlCommand::Connect {
            headset_id: headset.clone(),
        },
        ControlCommand::ConnectPinned {
            headset_id: headset.clone(),
        },
        ControlCommand::ConnectForAddHeadset {
            headset_id: headset.clone(),
        },
        ControlCommand::Disconnect,
        ControlCommand::Set {
            reference: None,
            command: HeadsetCommand::NoiseMode(NoiseMode::Anc),
        },
        ControlCommand::Set {
            reference: Some(reference.clone()),
            command: HeadsetCommand::NoiseCycle(cycle),
        },
        ControlCommand::Set {
            reference: Some(reference.clone()),
            command: HeadsetCommand::TouchBinding {
                side: "left".to_string(),
                gesture: "double_tap".to_string(),
                action: "play_pause".to_string(),
            },
        },
        ControlCommand::Set {
            reference: Some(reference.clone()),
            command: HeadsetCommand::CustomEqualizer(vec![1, -2, 3, 0, 4]),
        },
        ControlCommand::ClaimPrompt {
            reference,
            prompt_id: "prompt-1".to_string(),
        },
    ];

    for command in commands {
        let encoded = command.encode();
        assert_eq!(b"ORC", &encoded[..3]);
        assert_eq!(WIRE_FORMAT_VERSION, encoded[3]);
        let decoded = ControlCommand::decode(&encoded).expect("command decodes");
        assert_eq!(command, decoded);
    }
}

#[test]
fn command_decode_rejects_malformed_and_wrong_version_bytes() {
    assert!(matches!(
        ControlCommand::decode(b"corrupt"),
        Err(CodecError::Malformed { .. })
    ));
    assert!(matches!(
        ControlCommand::decode(b"ORC\x01{\"RefreshBondedHeadsets\":"),
        Err(CodecError::Malformed { .. })
    ));

    let mut wrong_version = ControlCommand::StartScan.encode();
    wrong_version[3] = WIRE_FORMAT_VERSION.wrapping_add(1);
    assert_eq!(
        Err(CodecError::UnsupportedVersion {
            expected: WIRE_FORMAT_VERSION,
            actual: WIRE_FORMAT_VERSION.wrapping_add(1),
        }),
        ControlCommand::decode(&wrong_version)
    );
}
