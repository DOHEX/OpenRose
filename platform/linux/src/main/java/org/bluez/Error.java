package org.bluez;

import org.freedesktop.dbus.exceptions.DBusExecutionException;

/** D-Bus error names are resolved by dbus-java to nested classes with these exact names. */
public final class Error {
    private Error() { }
    public static class NotAuthorized extends DBusExecutionException { public NotAuthorized(String message) { super(message); } }
    public static class NotPermitted extends DBusExecutionException { public NotPermitted(String message) { super(message); } }
    public static class NotSupported extends DBusExecutionException { public NotSupported(String message) { super(message); } }
    public static class InvalidArguments extends DBusExecutionException { public InvalidArguments(String message) { super(message); } }
    public static class InvalidValueLength extends DBusExecutionException { public InvalidValueLength(String message) { super(message); } }
    public static class InvalidOffset extends DBusExecutionException { public InvalidOffset(String message) { super(message); } }
    public static class NotConnected extends DBusExecutionException { public NotConnected(String message) { super(message); } }
    public static class AlreadyConnected extends DBusExecutionException { public AlreadyConnected(String message) { super(message); } }
    public static class InProgress extends DBusExecutionException { public InProgress(String message) { super(message); } }
    public static class NotReady extends DBusExecutionException { public NotReady(String message) { super(message); } }
    public static class Failed extends DBusExecutionException { public Failed(String message) { super(message); } }
}
