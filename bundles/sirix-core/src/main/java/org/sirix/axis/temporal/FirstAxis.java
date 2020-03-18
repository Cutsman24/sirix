package org.sirix.axis.temporal;

import org.sirix.api.NodeCursor;
import org.sirix.api.NodeReadOnlyTrx;
import org.sirix.api.NodeTrx;
import org.sirix.api.ResourceManager;
import org.sirix.axis.AbstractTemporalAxis;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Open the first revision and try to move to the node with the given node key.
 *
 * @author Johannes Lichtenberger
 *
 */
public final class FirstAxis<R extends NodeReadOnlyTrx & NodeCursor, W extends NodeTrx & NodeCursor>
    extends AbstractTemporalAxis<R, W> {

  /** Sirix {@link ResourceManager}. */
  private final ResourceManager<R, W> resourceManager;

  /** Node key to lookup and retrieve. */
  private final long nodeKey;

  /** Determines if it's the first call. */
  private boolean isFirst;

  /**
   * Constructor.
   *
   * @param resourceManager the resource manager
   * @param rtx the transactional cursor
   */
  public FirstAxis(final ResourceManager<R, W> resourceManager, final R rtx) {
    this.resourceManager = checkNotNull(resourceManager);
    nodeKey = rtx.getNodeKey();
    isFirst = true;
  }

  @Override
  protected R computeNext() {
    if (isFirst) {
      isFirst = false;
      final Optional<R> optionalRtx = resourceManager.getNodeReadTrxByRevisionNumber(1);

      final R rtx = optionalRtx.orElseGet(() -> resourceManager.beginNodeReadOnlyTrx(1));

      if (rtx.moveTo(nodeKey).hasMoved()) {
        return rtx;
      } else {
        rtx.close();
        return endOfData();
      }
    } else {
      return endOfData();
    }
  }

  @Override
  public ResourceManager<R, W> getResourceManager() {
    return resourceManager;
  }
}
