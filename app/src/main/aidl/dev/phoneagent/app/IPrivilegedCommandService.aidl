package dev.phoneagent.app;

// A typed operation name, never a raw shell command. The privileged process validates again.
interface IPrivilegedCommandService {
    String executeOperation(String operation, int value) = 0;
    void destroy() = 16777114;
}
