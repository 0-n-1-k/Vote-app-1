package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testBCryptHashing() {
    val password = "adminSecure!"
    val salt = org.mindrot.jbcrypt.BCrypt.gensalt(4)
    val hashed = org.mindrot.jbcrypt.BCrypt.hashpw(password, salt)
    assertNotNull(hashed)
    assertTrue(org.mindrot.jbcrypt.BCrypt.checkpw(password, hashed))
    assertFalse(org.mindrot.jbcrypt.BCrypt.checkpw("wrongPassword", hashed))
  }
}
