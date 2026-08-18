package views.shared;

import lombok.Builder;

@Builder
public record ScriptElementSettings(
    String src,
    String type,
    boolean async,
    boolean defer,
    String id) {

  public ScriptElementSettings {
    if (type == null || type.isBlank()) {
      type = "module";
    }
  }
}
