package com.example.governance.release;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface CommandRunner {
    CommandResult run(
            List<String> command,
            Map<String, String> environment,
            Path workspace,
            Duration timeout,
            long maxOutputBytes);
}
