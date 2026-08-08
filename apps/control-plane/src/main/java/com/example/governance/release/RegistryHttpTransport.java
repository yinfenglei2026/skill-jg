package com.example.governance.release;

import java.io.IOException;

public interface RegistryHttpTransport {
    RegistryHttpResponse send(RegistryHttpRequest request) throws IOException, InterruptedException;
}
