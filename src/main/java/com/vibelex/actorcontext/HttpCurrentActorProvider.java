package com.vibelex.actorcontext;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
public class HttpCurrentActorProvider implements CurrentActorProvider {
  private final HttpServletRequest request;
  private final Set<String> allowed;
  private final String defaultActor;

  public HttpCurrentActorProvider(
      HttpServletRequest request,
      @Value("${vibelex.actors}") String actors,
      @Value("${vibelex.default-actor}") String defaultActor) {
    this.request = request;
    this.allowed =
        Arrays.stream(actors.split(",")).map(String::trim).collect(Collectors.toUnmodifiableSet());
    this.defaultActor = defaultActor;
  }

  @Override
  public String currentActor() {
    String actor = request.getHeader("X-Actor-Id");
    actor = actor == null || actor.isBlank() ? defaultActor : actor.trim();
    if (!allowed.contains(actor)) throw new IllegalArgumentException("X-Actor-Id 不在固定操作者白名单中");
    return actor;
  }
}
