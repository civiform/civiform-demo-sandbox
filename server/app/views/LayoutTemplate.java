package views;

public enum LayoutTemplate {
  MAIN_LAYOUT("layout/MainLayout");

  private final String path;

  LayoutTemplate(String path) {
    this.path = path;
  }

  public String getPath() {
    return path;
  }
}
