package com.madscientiste.rcon.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Guards RconCommandSender against Hytale server API changes. A missing CommandSender method only
 * shows up at runtime as an AbstractMethodError (every RCON command then fails), so check it here.
 */
public class RconCommandSenderApiTest {

  // Loaded by name: the server jar is only on the test runtime classpath, which is also the
  // CommandSender this check is about.
  private static final String COMMAND_SENDER =
      "com.hypixel.hytale.server.core.command.system.CommandSender";

  /** The sender name methods of both server generations must exist and return "RCON". */
  @Test
  public void declaresBothSenderNameMethods() throws Exception {
    RconCommandSender sender = new RconCommandSender();
    for (String name : new String[] {"getUsername", "getDisplayName"}) {
      Method method = RconCommandSender.class.getDeclaredMethod(name);
      assertTrue(name + " must be public", Modifier.isPublic(method.getModifiers()));
      assertFalse(name + " must not be abstract", Modifier.isAbstract(method.getModifiers()));
      assertEquals(name + " return type", String.class, method.getReturnType());
      assertEquals(name + " value", "RCON", method.invoke(sender));
    }
  }

  /** Every abstract method of the CommandSender on the classpath must have an implementation. */
  @Test
  public void implementsEveryAbstractCommandSenderMethod() throws Exception {
    Class<?> commandSender = Class.forName(COMMAND_SENDER);
    assertTrue(
        "RconCommandSender must implement " + COMMAND_SENDER,
        commandSender.isAssignableFrom(RconCommandSender.class));

    List<String> missing = new ArrayList<>();
    for (Method required : commandSender.getMethods()) {
      if (!Modifier.isAbstract(required.getModifiers())) {
        continue; // default and static methods need no implementation
      }
      try {
        Method impl =
            RconCommandSender.class.getMethod(required.getName(), required.getParameterTypes());
        if (Modifier.isAbstract(impl.getModifiers())
            || !required.getReturnType().isAssignableFrom(impl.getReturnType())) {
          missing.add(required.toString());
        }
      } catch (NoSuchMethodException e) {
        missing.add(required.toString());
      }
    }
    if (!missing.isEmpty()) {
      fail("RconCommandSender does not implement: " + missing);
    }
  }
}
