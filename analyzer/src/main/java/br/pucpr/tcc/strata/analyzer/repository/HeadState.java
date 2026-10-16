package br.pucpr.tcc.strata.analyzer.repository;

public record HeadState(String commitId, String branch, boolean shallow) {}
