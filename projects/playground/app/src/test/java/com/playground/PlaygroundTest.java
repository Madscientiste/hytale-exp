package com.playground;

import static org.junit.Assert.*;

import org.junit.Test;

public class PlaygroundTest {
  @Test
  public void pluginJsonExists() {
    // Verify manifest.json exists in resources
    assertNotNull(getClass().getClassLoader().getResource("manifest.json"));
  }
}
