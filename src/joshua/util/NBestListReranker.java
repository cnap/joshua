package joshua.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import joshua.zmert.EvaluationMetric;

public class NBestListReranker extends JoshuaEval {
	public static void main(String[] args) {
		NBestListReranker nblr = new NBestListReranker();
String usage = "java joshua.util.NBestListReranker referencefilepath nbestlistpath metric metric_params ..";
		if (args.length < 2) {
			System.err.println(usage);
			System.exit(1);
		}
		try {
			nblr.loadReferences(args[0]);
		} catch (IOException e) {
			System.err.println(usage);
			System.err.println("Error loading references");
			e.printStackTrace();
			System.exit(1);
		}
		nblr.findMetric(args);
		try {
			nblr.loadNbestList(args[1]);
		} catch (IOException e) {
			System.err.println(usage);
			System.err.println("Error loading nbestList");
			e.printStackTrace();
			System.exit(1);
		}
	}
	
	private void findMetric(String[] args) {
		EvaluationMetric.set_knownMetrics();

		metricName = args[2];
		if (EvaluationMetric.knownMetricName(metricName)) {
			int optionCount = EvaluationMetric.metricOptionCount(metricName);
			metricOptions = new String[optionCount];
			for (int opt = 0; opt < optionCount; ++opt) {
				metricOptions[opt] = args[3 + opt];
			}
		} else {
			System.err.println("Unknown metric name " + metricName + ".");
			System.exit(10);
		}
		EvaluationMetric.set_numSentences(numSentences);
		EvaluationMetric.set_refsPerSen(1);
		EvaluationMetric.set_refSentences(refSentences);

		// do necessary initialization for the evaluation metric
		evalMetric = EvaluationMetric.getMetric(metricName, metricOptions);

		System.err.println("Processing " + numSentences + " sentences...");
	}

	private void rankCandidates(String[] topCand_str, int sentIndex,
 ArrayList<String> featureScores) {
		Map<Integer, Double> validCandidates = new TreeMap<Integer, Double>();
		int suffStatsCount = evalMetric.get_suffStatsCount();
		int[] IA = new int[topCand_str.length];
		for (int i = 0; i < topCand_str.length; ++i) {
			IA[i] = sentIndex;
		}
		int[][] SS = evalMetric.suffStats(topCand_str, IA);
		for (int i = 0; i < topCand_str.length; ++i) {
			int[] stats = new int[suffStatsCount];
			for (int s = 0; s < suffStatsCount; ++s) {
				stats[s] = SS[i][s];
			}
			double d = evalMetric.score(stats);
			if (d > 0) {
				validCandidates.put(i, d);
				System.out.println(sentIndex + " ||| " + topCand_str[i] + " ||| " + featureScores.get(i) + " ||| " + d);
			}
		}
		// if no valid candidates are found, just add them all
		if (validCandidates.size() == 0) {
			for (int i = 0; i < topCand_str.length; i++) {
				System.out.println(sentIndex + " ||| " + topCand_str[i]
						+ " ||| " + featureScores.get(i) + " ||| 0");
		    }
		}

		for (Map.Entry<Integer, Double> e : sortByValue(validCandidates).entrySet()) {
		    //			System.out.println(sentIndex + " ||| " + topCand_str[e.getKey()] + " ||| " + featureScores.get(e.getKey()) + " ||| " + e.getValue());

		}
		// for (Double d : validCandidates.descendingKeySet()) {
		// System.out.println(sentIndex + " ||| " + validCandidates.get(d)
		// + " ||| " + d);
		// }
	}

	public Map<Integer, Double> sortByValue(Map<Integer, Double> map) {
		List<Map.Entry<Integer, Double>> list = new LinkedList<Map.Entry<Integer, Double>>(map.entrySet());

		Collections.sort(list, new Comparator<Map.Entry<Integer, Double>>() {

			public int compare(Map.Entry<Integer, Double> m1, Map.Entry<Integer, Double> m2) {
				return (m2.getValue()).compareTo(m1.getValue());
			}

		});

		Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
		for (Map.Entry<Integer, Double> entry : list) {
			result.put(entry.getKey(), entry.getValue());
		}
		return result;
	}

	private void loadNbestList(String filename) throws IOException {
		InputStream is = new FileInputStream(new File(filename));
		BufferedReader br = new BufferedReader(new InputStreamReader(is, "utf8"));
		ArrayList<String> candidateSents = new ArrayList<String>();
		ArrayList<String> featureScores = new ArrayList<String>();
		int index = 0;
		String line;
		while ( (line = br.readLine()) != null) {
			String[] ss = line.split("\\s*\\|\\|\\|\\s*");
			int read_i = Integer.parseInt(ss[0]);
			if (read_i == index) {
				candidateSents.add(ss[1]);
				featureScores.add(ss[2]);
			}
			else {
				String[] candidatesToRank = new String[candidateSents.size()];
				candidateSents.toArray(candidatesToRank);
				rankCandidates(candidatesToRank, index, featureScores);
				candidateSents = new ArrayList<String>();
				featureScores = new ArrayList<String>();
				candidateSents.add(ss[1]);
				featureScores.add(ss[2]);
				index = read_i;
			}
		}
		//rank last sentence
		String[] candidatesToRank = new String[candidateSents.size()];
		candidateSents.toArray(candidatesToRank);
		rankCandidates(candidatesToRank, index, featureScores);

	}

	private void loadReferences(String filename) throws IOException {
		ArrayList<String> refs = new ArrayList<String>();
		InputStream is = new FileInputStream(new File(filename));
		BufferedReader br = new BufferedReader(new InputStreamReader(is, "utf8"));
		String line;
		while ( (line = br.readLine()) != null) {
			refs.add(line);
		}
		br.close();
		is.close();
		numSentences = refs.size();
		refSentences = new String[numSentences][];
		for (int i = 0; i < numSentences; i++) {
			refSentences[i] = new String[1];
			refSentences[i][0] = refs.get(i);
		}
	}
}
