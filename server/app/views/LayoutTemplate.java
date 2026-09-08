package views;

public enum LayoutTemplate {
  MAIN_LAYOUT("layout/MainLayout"),
  LOGIN_LAYOUT("layout/LoginLayout");

  private final String path;

  LayoutTemplate(String path) {
    this.path = path;
  }

  public String getPath() {
    return path;
  }
}
