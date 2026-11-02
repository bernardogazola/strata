package br.pucpr.tcc.strata.analyzer.graph;

import java.util.Arrays;
import java.util.Optional;

public final class CycleAssignment {

  private final Cycle[] cycles;
  private final int cycleCount;

  public CycleAssignment(Cycle[] cycles, int cycleCount) {
    this.cycles = cycles.clone();
    this.cycleCount = cycleCount;
  }

  public Optional<Cycle> cycleOf(int vertex) {
    return Optional.ofNullable(cycles[vertex]);
  }

  public int cycleCount() {
    return cycleCount;
  }

  public int verticesInCycles() {
    return (int) Arrays.stream(cycles).filter(cycle -> cycle != null).count();
  }

  public int size() {
    return cycles.length;
  }
}
