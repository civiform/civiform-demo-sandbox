package controllers;

import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;

public class HealthCheckController extends Controller {

  public Result health() {
    return ok(Json.newObject()
        .put("status", "UP")
        .put("service", "cf-sandbox-builder"));
  }

  public Result ready() {
    return ok(Json.newObject()
        .put("status", "READY")
        .put("service", "cf-sandbox-builder"));
  }
}
