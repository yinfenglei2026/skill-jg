package com.example.governance.release;

public record CommandResult(int exitCode, String stdout, String stderr) {
}
