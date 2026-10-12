package cloud.pocket.drive.shizuku;

// Runs inside the process Shizuku starts with shell rights.
interface IShell {
    // Runs a command and returns what it printed (standard output), or "" on failure.
    String run(in String[] command);
}
