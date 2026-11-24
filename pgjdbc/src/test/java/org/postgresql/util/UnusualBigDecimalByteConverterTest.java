/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Tests unusual binary representations of numeric values.
 * @author Brett Okken
 */
public class UnusualBigDecimalByteConverterTest {

  /**
   * Typically a number < 1 would have sections of leading '0' values represented in weight
   * rather than including as short values.
   */
  @Test
  public void test_4_leading_0() {
    //len 2
    //weight -1
    //scale 5
    final byte[] data = new byte[] {0, 2, -1, -1, 0, 0, 0, 5, 0, 0, 23, 112};
    final BigDecimal actual = (BigDecimal) ByteConverter.numeric(data);
    assertEquals(new BigDecimal("0.00006"), actual);
  }

  /**
   * The binary format keeps 14 bits of the scale, so 999999.99e-262144 used to be sent as
   * 999999.99.
   */
  @Test
  public void scaleTooLargeIsRejected() {
    assertRejected(new BigDecimal("999999.99e-262144"));
    assertRejected(new BigDecimal("-999999.99e-262144"));
    assertRejected(new BigDecimal(BigInteger.ONE, 16384));
    assertRejected(new BigDecimal(BigInteger.ZERO, 262144));
  }

  /**
   * The scale must be rejected before 10^scale is computed, otherwise a single value keeps the
   * thread busy for minutes.
   */
  @Test(timeout = 10000)
  public void hugeScaleIsRejectedQuickly() {
    assertRejected(new BigDecimal("1e-200000000"));
    assertRejected(new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE));
  }

  /**
   * The binary format keeps 16 bits of the weight, so 1e200000 used to be sent as a different
   * number.
   */
  @Test
  public void weightTooLargeIsRejected() {
    assertRejected(new BigDecimal(BigInteger.ONE, -200000));
    assertRejected(new BigDecimal(BigInteger.ONE.negate(), -200000));
    assertRejected(new BigDecimal(BigInteger.ONE, -131072));
  }

  @Test
  public void largestScaleAndWeightRoundTrip() {
    BigDecimalByteConverterTest.testBinary(new BigDecimal(BigInteger.ONE, 16383));
    BigDecimalByteConverterTest.testBinary(new BigDecimal("999999.99").setScale(16383));
    BigDecimalByteConverterTest.testBinary(new BigDecimal(BigInteger.ONE, -131068));
  }

  private static void assertRejected(BigDecimal number) {
    try {
      ByteConverter.numeric(number);
    } catch (IllegalArgumentException expected) {
      // the value cannot be represented in the binary numeric format
      return;
    }
    fail("binary numeric for " + number + " must be rejected");
  }
}
