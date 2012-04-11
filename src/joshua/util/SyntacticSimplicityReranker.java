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
import java.util.TreeMap;
import joshua.zmert.SyntacticSimplicityMetric;

public class SyntacticSimplicityReranker {
	SyntacticSimplicityMetric ssMetric;
	int numSentences;
	String[][] refSentences;
	boolean rankByParse = true;

	public static void main(String[] args) {
		SyntacticSimplicityReranker ssr = new SyntacticSimplicityReranker();
		String usage = "java joshua.util.SyntacicSimplicityReranker referencefilepath nbestlistpath [parse]";
		if (args.length < 2) {
			System.err.println(usage);
			System.exit(1);
		}
		try {
			ssr.loadReferences(args[0]);
		} catch (IOException e) {
			System.err.println(usage);
			System.err.println("Error loading references");
			e.printStackTrace();
			System.exit(1);
		}
		ssr.initializeMetric(args);
		if (args.length == 3) {
			ssr.rankByParse = true;
		}
		try {
			ssr.loadNbestList(args[1]);
		} catch (IOException e) {
			System.err.println(usage);
			System.err.println("Error loading nbestList");
			e.printStackTrace();
			System.exit(1);
		}
	}

	private void initializeMetric(String[] args) {
		String[] ss = new String[5];
		for (int i = 0; i < 5; i++) {
			ss[i] = args[i + 3];
		}
	    
		ssMetric = new SyntacticSimplicityMetric(ss);

		ssMetric.set_numSentences(numSentences);
		ssMetric.set_refsPerSen(1);
		ssMetric.set_refSentences(refSentences);
		try {
			ssMetric.loadSources();
		} catch (IOException e) {
			System.err.println("Error loading sources");
			e.printStackTrace();
		}
		ssMetric.initialize();

		System.err.println("Processing " + numSentences + " sentences...");
	}

	private void loadReferences(String filename) throws IOException {
		ArrayList<String> refs = new ArrayList<String>();
		InputStream is = new FileInputStream(new File(filename));
		BufferedReader br = new BufferedReader(new InputStreamReader(is, "utf8"));
		String line;
		while ((line = br.readLine()) != null) {
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

	private void rankCandidates(String[] candidates, int sentIndex, ArrayList<String> featureScores) {
		Map<Integer, Double> validCandidates = new TreeMap<Integer, Double>();
		if (!rankByParse) {

			int suffStatsCount = ssMetric.get_suffStatsCount();
			int[] IA = new int[candidates.length];
			for (int i = 0; i < candidates.length; ++i) {
				IA[i] = sentIndex;
			}
			int[][] SS = ssMetric.suffStats(candidates, IA);
			for (int i = 0; i < candidates.length; ++i) {
				int[] stats = new int[suffStatsCount];
				for (int s = 0; s < suffStatsCount; ++s) {
					stats[s] = SS[i][s];
				}
				double d = ssMetric.getRelativeScore(stats);
				if (d > 0) {
					if (validCandidates.containsKey(i))
						System.err.println("Found duplicate for sentence " + sentIndex + " val = " + d);
					validCandidates.put(i, d);
				}
			}
		} else {
			for (int i = 0; i < candidates.length; i++) {
				ssMetric.parse(candidates[i]);
				validCandidates.put(i, ssMetric.parseScores.get(candidates[i]));
			}
		}
		// if no valid candidates are found, just add them all
		if (validCandidates.size() == 0) {
			for (int i = 0; i < candidates.length; i++) {
				System.out.println(sentIndex + " ||| " + candidates[i] + " ||| " + featureScores.get(i) + " ||| 0");
			}
		}

		for (Map.Entry<Integer, Double> e : sortByValue(validCandidates).entrySet()) {
			System.out.println(sentIndex + " ||| " + candidates[e.getKey()] + " ||| " + featureScores.get(e.getKey()) + " ||| " + e.getValue());

		}

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
		while ((line = br.readLine()) != null) {
			String[] ss = line.split("\\s*\\|\\|\\|\\s*");
			int read_i = Integer.parseInt(ss[0]);
			if (read_i != index) {
				String[] candidatesToRank = new String[candidateSents.size()];
				candidateSents.toArray(candidatesToRank);
				rankCandidates(candidatesToRank, index, featureScores);
				candidateSents = new ArrayList<String>();
				featureScores = new ArrayList<String>();
				index = read_i;
			}
			candidateSents.add(ss[1]);
			featureScores.add(ss[2]);
		}
		// rank last sentence
		String[] candidatesToRank = new String[candidateSents.size()];
		candidateSents.toArray(candidatesToRank);
		rankCandidates(candidatesToRank, index, featureScores);

	}
}
