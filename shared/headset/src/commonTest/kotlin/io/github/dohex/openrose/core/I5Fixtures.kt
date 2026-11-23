package io.github.dohex.openrose.core

/**
 * Independent real i5 Linux initialization capture (rose_reverse/analysis/i5_live_read_2026-10-18.jsonl),
 * keyed by the query's response key. No setting hardware capture exists, so setting confirmations in the
 * session tests are synthetic.
 */
internal val i5Replies = mapOf(
    "0101" to "09ff00000101011100000164620000ffe2",
    "0602" to "09ff00000106020e000000010020",
    "0201" to "09ff00000102010b000118",
    "0603" to "09ff00000106030b00011e",
    "0801" to "09ff00000108010b00011e",
    "0d01" to "09ff0000010d010e00010205002d",
    "0401" to "09ff00000104011200060502040605030746",
    "0a01" to "09ff0000010a010b00001f",
    "0605" to "09ff00000106050e000101010126",
    "0c01" to "09ff0000010c010b000122",
    "0607" to "09ff00000106070b000223",
    "0604" to "09ff00000106040b00001e",
    "0e03" to "09ff0000010e030b000126",
)
