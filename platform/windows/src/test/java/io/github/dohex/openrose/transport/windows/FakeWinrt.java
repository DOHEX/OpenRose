package io.github.dohex.openrose.transport.windows;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;

/** Emulates the WinRT helper at its process boundary with recorded i5 initialization replies. */
public final class FakeWinrt {
    private static final Map<String, String> READS = Map.ofEntries(
            Map.entry("0101", "09ff00000101011100000164620000ffe2"),
            Map.entry("0602", "09ff00000106020e000000010020"),
            Map.entry("0201", "09ff00000102010b000118"),
            Map.entry("0603", "09ff00000106030b00011e"),
            Map.entry("0801", "09ff00000108010b00011e"),
            Map.entry("0d01", "09ff0000010d010e00010205002d"),
            Map.entry("0401", "09ff00000104011200060502040605030746"),
            Map.entry("0a01", "09ff0000010a010b00001f"),
            Map.entry("0605", "09ff00000106050e000101010126"),
            Map.entry("0c01", "09ff0000010c010b000122"),
            Map.entry("0607", "09ff00000106070b000223"),
            Map.entry("0604", "09ff00000106040b00001e"),
            Map.entry("0e03", "09ff0000010e030b000126")
    );

    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in));
        String mode = READS.get("0602");
        boolean dropReadback = false;
        boolean subscribed = false;
        for (String line; (line = input.readLine()) != null;) {
            var fields = line.split("\\t");
            String reply = "";
            switch (fields[1]) {
                case "discover" -> reply = "\t23\t1/00007777-0000-1000-8000-00805f9b34fb/w\t2/00008888-0000-1000-8000-00805f9b34fb/n";
                case "subscribe" -> subscribed = fields[2].equals("2") && fields[3].equals("NOTIFY");
                case "write" -> {
                    if (!subscribed || !fields[2].equals("1")) throw new IllegalStateException("Invalid GATT write");
                    String key = fields[4].substring(10,14);
                    if (key.equals("0682")) {
                        dropReadback = !fields[4].equals("08ee00000006820e00000000018d");
                        if (!dropReadback) mode = "09ff00000106020e000000000120";
                    }
                    String value = key.equals("0602") ? (dropReadback ? null : mode) : READS.get(key);
                    if (value != null) System.out.println("value\t2\t" + value);
                }
            }
            System.out.println(fields[0] + "\tok" + reply);
        }
    }
}
