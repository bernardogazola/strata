package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.path.CtRole;
import spoon.support.modelobs.EmptyModelChangeListener;

@SuppressWarnings("rawtypes")
final class CancellationListener extends EmptyModelChangeListener {

  private final BooleanSupplier cancelled;

  CancellationListener(BooleanSupplier cancelled) {
    this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
  }

  @Override
  public void onObjectUpdate(
      CtElement currentElement, CtRole role, CtElement newValue, CtElement oldValue) {
    check();
  }

  @Override
  public void onObjectUpdate(
      CtElement currentElement, CtRole role, Object newValue, Object oldValue) {
    check();
  }

  @Override
  public void onObjectDelete(CtElement currentElement, CtRole role, CtElement oldValue) {
    check();
  }

  @Override
  public void onListAdd(CtElement currentElement, CtRole role, List field, CtElement newValue) {
    check();
  }

  @Override
  public void onListAdd(
      CtElement currentElement, CtRole role, List field, int index, CtElement newValue) {
    check();
  }

  @Override
  public void onListDelete(
      CtElement currentElement, CtRole role, List field, Collection<? extends CtElement> oldValue) {
    check();
  }

  @Override
  public void onListDelete(
      CtElement currentElement, CtRole role, List field, int index, CtElement oldValue) {
    check();
  }

  @Override
  public void onListDeleteAll(CtElement currentElement, CtRole role, List field, List oldValue) {
    check();
  }

  @Override
  public <K, V> void onMapAdd(
      CtElement currentElement, CtRole role, Map<K, V> field, K key, CtElement newValue) {
    check();
  }

  @Override
  public <K, V> void onMapDelete(
      CtElement currentElement, CtRole role, Map<K, V> field, K key, CtElement oldValue) {
    check();
  }

  @Override
  public <K, V> void onMapDeleteAll(
      CtElement currentElement, CtRole role, Map<K, V> field, Map<K, V> oldValue) {
    check();
  }

  @Override
  public void onSetAdd(CtElement currentElement, CtRole role, Set field, CtElement newValue) {
    check();
  }

  @Override
  public <T extends Enum> void onSetAdd(
      CtElement currentElement, CtRole role, Set field, T newValue) {
    check();
  }

  @Override
  public void onSetDelete(CtElement currentElement, CtRole role, Set field, CtElement oldValue) {
    check();
  }

  @Override
  public void onSetDelete(
      CtElement currentElement, CtRole role, Set field, Collection<ModifierKind> oldValue) {
    check();
  }

  @Override
  public void onSetDelete(CtElement currentElement, CtRole role, Set field, ModifierKind oldValue) {
    check();
  }

  @Override
  public void onSetDeleteAll(CtElement currentElement, CtRole role, Set field, Set oldValue) {
    check();
  }

  private void check() {
    if (cancelled.getAsBoolean()) {
      throw new CancellationException(
          "análise de código cancelada no meio de uma mudança do modelo");
    }
  }
}
