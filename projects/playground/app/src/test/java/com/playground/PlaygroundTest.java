package com.playground;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaygroundTest {
    @Test
    public void pluginJsonExists() {
        // Verify manifest.json exists in resources
        assertNotNull(getClass().getClassLoader().getResource("manifest.json"));
    }
}
