package views.shared;

import com.google.common.collect.ImmutableList;
import lombok.Builder;
import views.LayoutType;

@Builder
public record LayoutParams(
    String pageTemplate,
    LayoutType layoutType,
    String favicon,
    ImmutableList<String> stylesheets,
    ImmutableList<ScriptElementSettings> headScripts,
    ImmutableList<ScriptElementSettings> bodyScripts) {}
