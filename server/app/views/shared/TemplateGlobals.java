package views.shared;

import java.util.Optional;
import lombok.Builder;

@Builder
public record TemplateGlobals(
    String pageTitle,
    String pageHeading,
    Optional<String> pageIntro,
    String cspNonce,
    String csrfToken,
    Boolean isDev) {}
