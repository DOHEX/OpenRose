use openrose_audio_probe::classify::{
    build_macos_snapshot, build_windows_snapshot, extract_address_from_uid, normalize_address,
};
use openrose_audio_probe::model::{
    AudioLayer, BluetoothStatus, BluetoothTransport, EvidenceStrength, MacAudioTransport,
    MacBluetoothObservation, MacCoreAudioObservation, WindowsAepServiceObservation,
    WindowsAudioEndpointObservation, WindowsDeviceObservation, WindowsEndpointFlow,
    WindowsEndpointState, WindowsTopologyFilter,
};

#[test]
fn normalizes_bluetooth_addresses() {
    assert_eq!(
        normalize_address("aa-bb-cc-dd-ee-ff"),
        Some("AA:BB:CC:DD:EE:FF".to_owned())
    );
    assert_eq!(
        normalize_address("01:23:45:67:89:ab"),
        Some("01:23:45:67:89:AB".to_owned())
    );
    assert_eq!(
        normalize_address("aabbccddeeff"),
        Some("AA:BB:CC:DD:EE:FF".to_owned())
    );
    assert_eq!(normalize_address("not-an-address"), None);
}

#[test]
fn windows_win10_separate_a2dp_and_hfp_endpoints_classify_directly() {
    let container = "{11111111-2222-3333-4444-555555555555}".to_owned();
    let devices = vec![WindowsDeviceObservation {
        id: "Bluetooth#00:11:22:33:44:55".to_owned(),
        name: "ROSE Ceramics X".to_owned(),
        address: Some("00:11:22:33:44:55".to_owned()),
        container_id: Some(container.clone()),
        transport: BluetoothTransport::Classic,
        is_connected: true,
    }];
    let endpoints = vec![
        WindowsAudioEndpointObservation {
            id: "ep-a2dp".to_owned(),
            container_id: Some(container.clone()),
            friendly_name: "Headphones (ROSE Ceramics X Stereo)".to_owned(),
            device_desc: Some("Headphones".to_owned()),
            flow: WindowsEndpointFlow::Render,
            state: WindowsEndpointState::Active,
            topology_filters: vec![WindowsTopologyFilter {
                id: "flt-a2dp".to_owned(),
                enumerator: Some("BTHENUM".to_owned()),
                instance_id: Some(
                    "BTHENUM\\{0000110b-0000-1000-8000-00805f9b34fb}_VID&00010002\\7&1234"
                        .to_owned(),
                ),
                friendly_name: Some("ROSE Ceramics X Stereo".to_owned()),
            }],
            capture_session_active: false,
            sample_rate_hz: Some(48000),
            channels: Some(2),
        },
        WindowsAudioEndpointObservation {
            id: "ep-hfp".to_owned(),
            container_id: Some(container),
            friendly_name: "Headset (ROSE Ceramics X Hands-Free)".to_owned(),
            device_desc: Some("Headset".to_owned()),
            flow: WindowsEndpointFlow::Capture,
            state: WindowsEndpointState::Unplugged,
            topology_filters: vec![WindowsTopologyFilter {
                id: "flt-hfp".to_owned(),
                enumerator: Some("BTHHFENUM".to_owned()),
                instance_id: Some("BTHHFENUM\\BthHFPAudio\\8&5678".to_owned()),
                friendly_name: Some("ROSE Ceramics X Hands-Free".to_owned()),
            }],
            capture_session_active: false,
            sample_rate_hz: None,
            channels: None,
        },
    ];

    let snapshot = build_windows_snapshot(BluetoothStatus::available(), &devices, &[], &endpoints);
    assert_eq!(snapshot.devices.len(), 1);
    let headset = &snapshot.devices[0];
    assert_eq!(headset.assessment.layer, AudioLayer::A2dp);
    assert_eq!(headset.assessment.strength, EvidenceStrength::Direct);
    assert_eq!(headset.assessment.direct_layer, AudioLayer::A2dp);
    assert!(headset.device_connected);
}

#[test]
fn windows_win11_unified_classic_endpoint_infers_a2dp_vs_hfp_from_capture() {
    let container = "{22222222-2222-3333-4444-555555555555}".to_owned();
    let devices = vec![
        WindowsDeviceObservation {
            id: "Bluetooth#AA:BB:CC:DD:EE:01".to_owned(),
            name: "Earfree i5".to_owned(),
            address: Some("aa-bb-cc-dd-ee-01".to_owned()),
            container_id: Some(container.clone()),
            transport: BluetoothTransport::Classic,
            is_connected: true,
        },
        WindowsDeviceObservation {
            id: "BluetoothLE#AA:BB:CC:DD:EE:01".to_owned(),
            name: "Earfree i5".to_owned(),
            address: Some("aa-bb-cc-dd-ee-01".to_owned()),
            container_id: Some(container.clone()),
            transport: BluetoothTransport::Le,
            is_connected: true,
        },
    ];
    let idle_endpoints = vec![
        WindowsAudioEndpointObservation {
            id: "ep-render".to_owned(),
            container_id: Some(container.clone()),
            friendly_name: "Headphones (Earfree i5)".to_owned(),
            device_desc: Some("Headphones".to_owned()),
            flow: WindowsEndpointFlow::Render,
            state: WindowsEndpointState::Active,
            topology_filters: vec![
                WindowsTopologyFilter {
                    id: "flt-a2dp".to_owned(),
                    enumerator: Some("BTHENUM".to_owned()),
                    instance_id: Some(
                        "BTHENUM\\{0000110b-0000-1000-8000-00805f9b34fb}_LOCALMFG\\7&1".to_owned(),
                    ),
                    friendly_name: Some("Earfree i5".to_owned()),
                },
                WindowsTopologyFilter {
                    id: "flt-hfp".to_owned(),
                    enumerator: Some("BTHHFENUM".to_owned()),
                    instance_id: Some("BTHHFENUM\\BthHFPAudio\\8&1".to_owned()),
                    friendly_name: Some("Earfree i5".to_owned()),
                },
            ],
            capture_session_active: false,
            sample_rate_hz: Some(48000),
            channels: Some(2),
        },
        WindowsAudioEndpointObservation {
            id: "ep-capture".to_owned(),
            container_id: Some(container.clone()),
            friendly_name: "Headset (Earfree i5)".to_owned(),
            device_desc: Some("Headset".to_owned()),
            flow: WindowsEndpointFlow::Capture,
            state: WindowsEndpointState::Active,
            topology_filters: vec![WindowsTopologyFilter {
                id: "flt-hfp".to_owned(),
                enumerator: Some("BTHHFENUM".to_owned()),
                instance_id: Some("BTHHFENUM\\BthHFPAudio\\8&1".to_owned()),
                friendly_name: Some("Earfree i5".to_owned()),
            }],
            capture_session_active: false,
            sample_rate_hz: Some(16000),
            channels: Some(1),
        },
    ];

    let idle = build_windows_snapshot(BluetoothStatus::available(), &devices, &[], &idle_endpoints);
    assert_eq!(idle.devices.len(), 1);
    assert_eq!(
        idle.devices[0].transports,
        vec![BluetoothTransport::Classic, BluetoothTransport::Le]
    );
    assert_eq!(idle.devices[0].assessment.layer, AudioLayer::A2dp);
    assert_eq!(idle.devices[0].assessment.strength, EvidenceStrength::Inferred);
    assert_eq!(
        idle.devices[0].assessment.direct_layer,
        AudioLayer::ClassicAudio
    );

    let mut call_endpoints = idle_endpoints;
    call_endpoints[1].capture_session_active = true;
    let in_call =
        build_windows_snapshot(BluetoothStatus::available(), &devices, &[], &call_endpoints);
    assert_eq!(in_call.devices[0].assessment.layer, AudioLayer::Hfp);
    assert_eq!(
        in_call.devices[0].assessment.strength,
        EvidenceStrength::Inferred
    );
    assert_eq!(
        in_call.devices[0].assessment.direct_layer,
        AudioLayer::ClassicAudio
    );
}

#[test]
fn windows_le_audio_and_device_level_only_classify_distinctly() {
    let le_container = "{33333333-2222-3333-4444-555555555555}".to_owned();
    let acl_container = "{44444444-2222-3333-4444-555555555555}".to_owned();
    let devices = vec![
        WindowsDeviceObservation {
            id: "BluetoothLE#11:22:33:44:55:66".to_owned(),
            name: "LE Buds".to_owned(),
            address: Some("11:22:33:44:55:66".to_owned()),
            container_id: Some(le_container.clone()),
            transport: BluetoothTransport::Le,
            is_connected: true,
        },
        WindowsDeviceObservation {
            id: "Bluetooth#66:55:44:33:22:11".to_owned(),
            name: "Idle Headset".to_owned(),
            address: Some("66:55:44:33:22:11".to_owned()),
            container_id: Some(acl_container),
            transport: BluetoothTransport::Classic,
            is_connected: true,
        },
    ];
    let services = vec![WindowsAepServiceObservation {
        container_id: Some(le_container.clone()),
        address: Some("11:22:33:44:55:66".to_owned()),
        service_class_id: "0000184e-0000-1000-8000-00805f9b34fb".to_owned(),
        is_connected: true,
    }];
    let endpoints = vec![WindowsAudioEndpointObservation {
        id: "ep-lea".to_owned(),
        container_id: Some(le_container),
        friendly_name: "Headphones (LE Buds)".to_owned(),
        device_desc: Some("Headphones".to_owned()),
        flow: WindowsEndpointFlow::Render,
        state: WindowsEndpointState::Active,
        topology_filters: vec![WindowsTopologyFilter {
            id: "flt-lea".to_owned(),
            enumerator: Some("BTHLE".to_owned()),
            instance_id: Some("BTHLE\\Dev_112233445566\\8&9".to_owned()),
            friendly_name: Some("LE Buds".to_owned()),
        }],
        capture_session_active: false,
        sample_rate_hz: Some(48000),
        channels: Some(2),
    }];

    let snapshot =
        build_windows_snapshot(BluetoothStatus::available(), &devices, &services, &endpoints);
    assert_eq!(snapshot.devices.len(), 2);

    let le_buds = snapshot
        .devices
        .iter()
        .find(|d| d.name == "LE Buds")
        .unwrap();
    assert_eq!(le_buds.assessment.layer, AudioLayer::LeAudio);
    assert_eq!(le_buds.assessment.strength, EvidenceStrength::Direct);
    assert_eq!(le_buds.assessment.direct_layer, AudioLayer::LeAudio);
    assert!(le_buds.advertised_profiles.iter().any(|p| p == "le-audio-ascs"));

    let idle = snapshot
        .devices
        .iter()
        .find(|d| d.name == "Idle Headset")
        .unwrap();
    assert_eq!(idle.assessment.layer, AudioLayer::DeviceLevelOnly);
    assert_eq!(idle.assessment.strength, EvidenceStrength::Direct);
    assert!(!idle.assessment.layer.is_audio_connected());
}

#[test]
fn extracts_bluetooth_address_from_macos_coreaudio_uids() {
    assert_eq!(
        extract_address_from_uid("aa-bb-cc-dd-ee-ff:output"),
        Some("AA:BB:CC:DD:EE:FF".to_owned())
    );
    assert_eq!(
        extract_address_from_uid("01:23:45:67:89:ab:input"),
        Some("01:23:45:67:89:AB".to_owned())
    );
    assert_eq!(
        extract_address_from_uid("10-20-30-40-50-60-tacl"),
        Some("10:20:30:40:50:60".to_owned())
    );
    assert_eq!(extract_address_from_uid("BuiltInSpeakerDevice"), None);
}

#[test]
fn macos_correlates_iobluetooth_and_coreaudio_for_a2dp_hfp_le_audio_and_acl() {
    let bt_devices = vec![
        MacBluetoothObservation {
            name: "Earfree i5".to_owned(),
            address: "aa-bb-cc-dd-ee-01".to_owned(),
            connected: true,
            hands_free_gateway: false,
            hands_free_device: true,
            sdp_profiles: vec!["a2dp-sink".to_owned(), "hfp".to_owned()],
        },
        MacBluetoothObservation {
            name: "Idle Classic".to_owned(),
            address: "aa-bb-cc-dd-ee-02".to_owned(),
            connected: true,
            hands_free_gateway: false,
            hands_free_device: false,
            sdp_profiles: vec!["a2dp-sink".to_owned()],
        },
    ];
    let a2dp_audio = vec![
        MacCoreAudioObservation {
            device_id: 42,
            uid: "aa-bb-cc-dd-ee-01:output".to_owned(),
            name: "Earfree i5".to_owned(),
            transport: MacAudioTransport::BluetoothClassic,
            alive: true,
            output_channels: 2,
            input_channels: 0,
            nominal_sample_rate_hz: Some(48000.0),
            output_running: true,
            input_running: false,
        },
        MacCoreAudioObservation {
            device_id: 43,
            uid: "aa-bb-cc-dd-ee-01:input".to_owned(),
            name: "Earfree i5".to_owned(),
            transport: MacAudioTransport::BluetoothClassic,
            alive: true,
            output_channels: 0,
            input_channels: 1,
            nominal_sample_rate_hz: Some(16000.0),
            output_running: false,
            input_running: false,
        },
        MacCoreAudioObservation {
            device_id: 99,
            uid: "ble-audio-opaque-uid".to_owned(),
            name: "LE Buds".to_owned(),
            transport: MacAudioTransport::BluetoothLe,
            alive: true,
            output_channels: 2,
            input_channels: 1,
            nominal_sample_rate_hz: Some(48000.0),
            output_running: false,
            input_running: false,
        },
    ];

    let snapshot = build_macos_snapshot(BluetoothStatus::available(), &bt_devices, &a2dp_audio);
    assert_eq!(snapshot.devices.len(), 3);

    let i5 = snapshot
        .devices
        .iter()
        .find(|d| d.name == "Earfree i5")
        .unwrap();
    assert_eq!(i5.assessment.layer, AudioLayer::A2dp);
    assert_eq!(i5.assessment.strength, EvidenceStrength::Inferred);
    assert_eq!(i5.assessment.direct_layer, AudioLayer::ClassicAudio);
    assert_eq!(i5.address.as_deref(), Some("AA:BB:CC:DD:EE:01"));

    let idle = snapshot
        .devices
        .iter()
        .find(|d| d.name == "Idle Classic")
        .unwrap();
    assert_eq!(idle.assessment.layer, AudioLayer::DeviceLevelOnly);
    assert_eq!(idle.assessment.strength, EvidenceStrength::Direct);

    let lea = snapshot
        .devices
        .iter()
        .find(|d| d.name == "LE Buds")
        .unwrap();
    assert_eq!(lea.assessment.layer, AudioLayer::LeAudio);
    assert_eq!(lea.assessment.strength, EvidenceStrength::Direct);
    assert_eq!(lea.assessment.direct_layer, AudioLayer::LeAudio);
    assert_eq!(lea.transports, vec![BluetoothTransport::Le]);

    let mut hfp_audio = a2dp_audio;
    hfp_audio[1].input_running = true;
    hfp_audio[0].nominal_sample_rate_hz = Some(16000.0);
    let hfp_snapshot = build_macos_snapshot(BluetoothStatus::available(), &bt_devices, &hfp_audio);
    let i5_hfp = hfp_snapshot
        .devices
        .iter()
        .find(|d| d.name == "Earfree i5")
        .unwrap();
    assert_eq!(i5_hfp.assessment.layer, AudioLayer::Hfp);
    assert_eq!(i5_hfp.assessment.strength, EvidenceStrength::Inferred);
    assert_eq!(i5_hfp.assessment.direct_layer, AudioLayer::ClassicAudio);
}

#[test]
fn macos_falls_back_to_name_match_when_coreaudio_uid_lacks_mac() {
    let bt_devices = vec![MacBluetoothObservation {
        name: "ROSE Ceramics X".to_owned(),
        address: "11-22-33-44-55-66".to_owned(),
        connected: true,
        hands_free_gateway: false,
        hands_free_device: true,
        sdp_profiles: vec!["a2dp-sink".to_owned()],
    }];
    let audio = vec![MacCoreAudioObservation {
        device_id: 77,
        uid: "opaque-coreaudio-uid".to_owned(),
        name: "rose ceramics x".to_owned(),
        transport: MacAudioTransport::BluetoothClassic,
        alive: true,
        output_channels: 2,
        input_channels: 0,
        nominal_sample_rate_hz: Some(44100.0),
        output_running: false,
        input_running: false,
    }];

    let snapshot = build_macos_snapshot(BluetoothStatus::available(), &bt_devices, &audio);
    assert_eq!(snapshot.devices.len(), 1);
    assert_eq!(snapshot.devices[0].address.as_deref(), Some("11:22:33:44:55:66"));
    assert_eq!(snapshot.devices[0].assessment.layer, AudioLayer::A2dp);
}

#[test]
fn diff_emits_audio_connect_layer_change_disconnect_and_link_events() {
    use openrose_audio_probe::diff::{diff_snapshots, filter_snapshot, ProbeEvent};
    use openrose_audio_probe::model::{DeviceSnapshot, LayerAssessment, ProbeSnapshot};

    let make_device = |connected: bool, layer: AudioLayer, strength: EvidenceStrength, direct: AudioLayer| {
        DeviceSnapshot {
            id: "AA:BB:CC:DD:EE:01".to_owned(),
            name: "Earfree i5".to_owned(),
            address: Some("AA:BB:CC:DD:EE:01".to_owned()),
            container_id: None,
            transports: vec![BluetoothTransport::Classic],
            device_connected: connected,
            assessment: LayerAssessment {
                layer,
                strength,
                direct_layer: direct,
                layers_present: vec![layer],
                reasons: vec!["test".to_owned()],
            },
            endpoints: Vec::new(),
            advertised_profiles: Vec::new(),
        }
    };

    let s0 = ProbeSnapshot {
        platform: "windows".to_owned(),
        bluetooth: BluetoothStatus::available(),
        devices: vec![make_device(false, AudioLayer::None, EvidenceStrength::Direct, AudioLayer::None)],
    };
    let s1 = ProbeSnapshot {
        platform: "windows".to_owned(),
        bluetooth: BluetoothStatus::available(),
        devices: vec![make_device(
            true,
            AudioLayer::A2dp,
            EvidenceStrength::Inferred,
            AudioLayer::ClassicAudio,
        )],
    };
    let s2 = ProbeSnapshot {
        platform: "windows".to_owned(),
        bluetooth: BluetoothStatus::available(),
        devices: vec![make_device(
            true,
            AudioLayer::Hfp,
            EvidenceStrength::Inferred,
            AudioLayer::ClassicAudio,
        )],
    };
    let s3 = ProbeSnapshot {
        platform: "windows".to_owned(),
        bluetooth: BluetoothStatus::available(),
        devices: vec![make_device(
            true,
            AudioLayer::DeviceLevelOnly,
            EvidenceStrength::Direct,
            AudioLayer::DeviceLevelOnly,
        )],
    };

    let e01 = diff_snapshots(&s0, &s1);
    assert!(e01.iter().any(|e| matches!(
        e,
        ProbeEvent::AudioConnected {
            layer: AudioLayer::A2dp,
            strength: EvidenceStrength::Inferred,
            direct_layer: AudioLayer::ClassicAudio,
            ..
        }
    )));
    assert!(e01
        .iter()
        .any(|e| matches!(e, ProbeEvent::DeviceLinkChanged { connected: true, .. })));

    let e12 = diff_snapshots(&s1, &s2);
    assert_eq!(e12.len(), 1);
    assert!(matches!(
        &e12[0],
        ProbeEvent::AudioLayerChanged {
            from_layer: AudioLayer::A2dp,
            to_layer: AudioLayer::Hfp,
            ..
        }
    ));

    let e23 = diff_snapshots(&s2, &s3);
    assert_eq!(e23.len(), 1);
    assert!(matches!(
        &e23[0],
        ProbeEvent::AudioDisconnected {
            previous_layer: AudioLayer::Hfp,
            device_still_connected: true,
            ..
        }
    ));

    let filtered = filter_snapshot(&s1, Some("i5"));
    assert_eq!(filtered.devices.len(), 1);
    let empty = filter_snapshot(&s1, Some("nonexistent"));
    assert!(empty.devices.is_empty());
}

#[test]
fn parses_cli_flags_and_rejects_unknown_arguments() {
    use openrose_audio_probe::cli::parse_args;

    let opts = parse_args(["--once", "--json", "--filter", "i5", "--poll-ms", "750"]).unwrap();
    assert!(opts.once);
    assert!(opts.json);
    assert_eq!(opts.filter.as_deref(), Some("i5"));
    assert_eq!(opts.poll_ms, 750);
    assert!(!opts.show_help);

    let help = parse_args(["-h"]).unwrap();
    assert!(help.show_help);

    assert!(parse_args(["--bogus"]).is_err());
    assert!(parse_args(["--poll-ms", "0"]).is_err());
}

#[test]
fn formats_text_and_jsonl_with_layer_strength_details() {
    use openrose_audio_probe::diff::ProbeEvent;
    use openrose_audio_probe::format::{
        format_event_json, format_event_text, format_snapshot_json, format_snapshot_text,
    };
    use openrose_audio_probe::model::{DeviceSnapshot, LayerAssessment, ProbeSnapshot};

    let snapshot = ProbeSnapshot {
        platform: "macos".to_owned(),
        bluetooth: BluetoothStatus::available(),
        devices: vec![DeviceSnapshot {
            id: "AA:BB:CC:DD:EE:01".to_owned(),
            name: "Earfree i5".to_owned(),
            address: Some("AA:BB:CC:DD:EE:01".to_owned()),
            container_id: None,
            transports: vec![BluetoothTransport::Classic],
            device_connected: true,
            assessment: LayerAssessment {
                layer: AudioLayer::A2dp,
                strength: EvidenceStrength::Inferred,
                direct_layer: AudioLayer::ClassicAudio,
                layers_present: vec![AudioLayer::ClassicAudio, AudioLayer::A2dp],
                reasons: vec!["coreaudio-classic-bt-output-present-input-idle".to_owned()],
            },
            endpoints: vec!["id=42 bt-classic uid=aa-bb-cc-dd-ee-01:output out=2ch in=0ch 48000Hz".to_owned()],
            advertised_profiles: vec!["a2dp-sink".to_owned(), "hfp".to_owned()],
        }],
    };

    let text = format_snapshot_text("12:00:00", &snapshot);
    assert!(text.contains("Earfree i5"));
    assert!(text.contains("a2dp"));
    assert!(text.contains("inferred"));
    assert!(text.contains("classic-audio"));

    let json_text = format_snapshot_json("12:00:00", &snapshot);
    let parsed: serde_json::Value = serde_json::from_str(&json_text).unwrap();
    assert_eq!(parsed["type"], "snapshot");
    assert_eq!(parsed["snapshot"]["devices"][0]["assessment"]["layer"], "a2dp");

    let event = ProbeEvent::AudioConnected {
        id: "AA:BB:CC:DD:EE:01".to_owned(),
        name: "Earfree i5".to_owned(),
        address: Some("AA:BB:CC:DD:EE:01".to_owned()),
        layer: AudioLayer::A2dp,
        strength: EvidenceStrength::Inferred,
        direct_layer: AudioLayer::ClassicAudio,
        reasons: vec!["coreaudio-classic-bt-output-present-input-idle".to_owned()],
    };
    let ev_text = format_event_text("12:00:01", &event);
    assert!(ev_text.contains("AUDIO_CONNECTED"));
    assert!(ev_text.contains("a2dp"));

    let ev_json = format_event_json("12:00:01", &event);
    let ev_parsed: serde_json::Value = serde_json::from_str(&ev_json).unwrap();
    assert_eq!(ev_parsed["type"], "event");
    assert_eq!(ev_parsed["event"]["kind"], "audio-connected");
}
