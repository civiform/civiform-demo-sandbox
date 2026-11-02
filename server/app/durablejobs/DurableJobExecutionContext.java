package durablejobs;

import static com.google.common.base.Preconditions.checkNotNull;

import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.pekko.actor.ActorSystem;
import play.libs.concurrent.CustomExecutionContext;

/** Dedicated thread pool for executing {@link DurableJob}s without starving HTTP request threads. */
@Singleton
public class DurableJobExecutionContext extends CustomExecutionContext {

  @Inject
  public DurableJobExecutionContext(ActorSystem actorSystem) {
    super(checkNotNull(actorSystem), "durable_jobs.dispatcher");
  }
}
