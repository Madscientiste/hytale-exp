package com.madscientiste.playground;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class PlaygroundTest {
  @Test
  public void pluginJsonExists() {
    // Verify manifest.json exists in resources
    assertNotNull(getClass().getClassLoader().getResource("manifest.json"));
  }
}
