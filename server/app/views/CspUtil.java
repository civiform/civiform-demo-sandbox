package views;

import play.mvc.Http.RequestHeader;

public final class CspUtil {
  private CspUtil() {}

  public static String getNonce(RequestHeader request) {
    // Return empty or request header nonce if present
    return "";
  }
}
