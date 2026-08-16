package com.ghostchu.quickshop.benchmark;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares benchmark result JSON files and prints a markdown table.
 * <p>
 * Usage: {@code mvn exec:java -Dexec.mainClass=...Compare -Dexec.args="<baselineJson> <candidateJson> [<candidateJson2> ...]"}
 * Baseline may point at a directory containing several fork files; they are aggregated by
 * median before comparison. Positive delta % means the candidate is faster.
 */
public final class Compare {

  private static final Gson GSON = new Gson();
  private static final Type REPORT_TYPE = new TypeToken<Map<String, Object>>() {

  }.getType();

  private Compare() {

  }

  public static void main(final String[] args) throws IOException {

    if(args.length < 2) {
      System.err.println("usage: Compare <baselineJsonOrDir> <candidateJsonOrDir> [...moreCandidates]");
      System.exit(1);
    }

    final Map<String, Double> baseline = aggregate(args[0]);
    System.out.println("# Benchmark comparison");
    System.out.println();
    System.out.println("Baseline: `" + args[0] + "`");
    System.out.println();

    final List<String> names = new ArrayList<>();
    final List<Map<String, Double>> candidates = new ArrayList<>();
    for(int i = 1; i < args.length; i++) {
      final Map<String, Double> candidate = aggregate(args[i]);
      names.add(Path.of(args[i]).getFileName().toString().replaceFirst("\\.json$", "").replaceFirst("-fork\\d+$", ""));
      candidates.add(candidate);
    }

    final StringBuilder header = new StringBuilder("| case | baseline ns/op |");
    final StringBuilder divider = new StringBuilder("|---|---:|");
    for(final String name : names) {
      header.append(" ").append(name).append(" ns/op | ").append(name).append(" Δ |");
      divider.append("---:|---:|");
    }
    System.out.println(header);
    System.out.println(divider);

    final List<String> allCases = new ArrayList<>(baseline.keySet());
    for(final Map<String, Double> candidate : candidates) {
      for(final String c : candidate.keySet()) {
        if(!allCases.contains(c)) {
          allCases.add(c);
        }
      }
    }
    for(final String caseName : allCases) {
      final Double base = baseline.get(caseName);
      final StringBuilder row = new StringBuilder("| ").append(caseName).append(" | ");
      if(base == null) {
        row.append("n/a | ");
        for(int i = 0; i < candidates.size(); i++) {
          row.append("n/a | n/a | ");
        }
        System.out.println(row.toString().replaceFirst(" \\| $", " |"));
        continue;
      }
      row.append(String.format("%,.1f | ", base));
      for(final Map<String, Double> candidate : candidates) {
        final Double value = candidate.get(caseName);
        if(value == null) {
          row.append("n/a | n/a | ");
        } else {
          final double delta = (base - value) / base * 100.0d;
          row.append(String.format("%,.1f | %s%.1f%% | ", value, delta >= 0? "−" : "+", Math.abs(delta)));
        }
      }
      System.out.println(row.toString().replaceFirst(" \\| $", " |"));
    }
  }

  /**
   * Aggregates one or more fork JSON files (when the path is a directory every *.json in
   * it is loaded). Returns case name → median of per-fork medians.
   */
  private static Map<String, Double> aggregate(final String pathStr) throws IOException {

    final Path path = Path.of(pathStr);
    final List<Path> files = new ArrayList<>();
    if(Files.isDirectory(path)) {
      try(final var stream = Files.list(path)) {
        stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
      }
    } else {
      files.add(path);
    }
    if(files.isEmpty()) {
      throw new IOException("No JSON result files found at " + pathStr);
    }

    // case -> list of fork medians
    final Map<String, List<Double>> collected = new LinkedHashMap<>();
    for(final Path file : files) {
      final Map<String, Object> report = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), REPORT_TYPE);
      final List<?> cases = (List<?>)report.get("cases");
      if(cases == null) {
        continue;
      }
      for(final Object entryObj : cases) {
        final Map<?, ?> entry = (Map<?, ?>)entryObj;
        final String name = String.valueOf(entry.get("name"));
        final Number median = (Number)entry.get("medianNsPerOp");
        collected.computeIfAbsent(name, k->new ArrayList<>()).add(median.doubleValue());
      }
    }
    final Map<String, Double> aggregated = new TreeMap<>();
    collected.forEach((name, medians)->aggregated.put(name, BenchHarness.median(medians)));
    return aggregated;
  }
}
