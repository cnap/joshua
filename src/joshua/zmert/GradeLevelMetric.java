package joshua.zmert;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GradeLevelMetric extends EvaluationMetric {

	protected String[] srcSentences;
	private Pattern syllable = Pattern.compile("([^aeiouy]*[aeiouy]+)"); // matches
																			// C*V+
	private Pattern silentE = Pattern.compile("[^aeiou]e$");
	protected final int CAND_TOKEN_LEN=0;
	protected final int CAND_SYLL_LEN=1;
	private final int REF_TOKEN_LEN=2;
	private final int REF_SYLL_LEN=3;
	private final int SRC_TOKEN_LEN=4;
	private final int SRC_SYLL_LEN=5;
	protected int use_penalty = 0;
	double target = 10.0; //

	public GradeLevelMetric() {
		initialize();
	}
	public GradeLevelMetric(String[] options) {
		initialize();
		use_penalty = Integer.parseInt(options[1]);
		if (use_penalty != 0 && use_penalty != 1) {
			System.err.println("Penalty must be 0 or 1");
			System.exit(1);
		}
		try {
			loadSources(options[0]);
		} catch (IOException e) {
			System.err.println("Error loading the source sentences from "+options[0]);
			e.printStackTrace();
			System.exit(1);
		}
	}

	private void loadSources(String filepath) throws IOException {
		BufferedReader br = new BufferedReader(new FileReader(filepath));
		String line;
		srcSentences= new String[numSentences];
		int i = 0;
		while ( i < numSentences && (line=br.readLine()) != null ) {
			srcSentences[i] = line.trim();
			i++;
		}
	}

	@Override
	public void initialize() {
		metricName = "GRADE_LEVEL";
		toBeMinimized = true;
		suffStatsCount = 4 + 2;
	}

	@Override
	public double bestPossibleScore() { return 0; }
	@Override
	public double worstPossibleScore() { return 50; }

	@Override
	public int[] suffStats(String cand_str, int i) {
		int[] stats = new int[suffStatsCount];
		String[] candidate_tokens;
		String [] reference_tokens = refSentences[i][0].split("\\s+");
		String [] source_tokens = srcSentences[i].split("\\s+");

		if (!cand_str.equals("")) candidate_tokens = cand_str.split("\\s+");
		else candidate_tokens = new String[0];

		// drop "_OOV" marker
		for (int j = 0; j < candidate_tokens.length; j++) {
			if (candidate_tokens[j].endsWith("_OOV"))
				candidate_tokens[j] = candidate_tokens[j].substring(0, candidate_tokens[j].length() - 4);
		}

		// token length
		stats[CAND_TOKEN_LEN] = candidate_tokens.length;
		stats[REF_TOKEN_LEN] = reference_tokens.length;
		stats[SRC_TOKEN_LEN] = source_tokens.length;

		// syllable length
		stats[CAND_SYLL_LEN] = countTotalSyllables(candidate_tokens); ///candidate_words.length;
		stats[REF_SYLL_LEN] = countTotalSyllables(reference_tokens);
		stats[SRC_SYLL_LEN] = countTotalSyllables(source_tokens); ///refSentences[i][sourceReferenceIndex].split("\\s+").length;

		return stats;
	}

	public int countTotalSyllables(String[] ss) {
		int count = 0;
		for (String s : ss) count+=countSyllables(s);
		return count;
	}

	public int countSyllables(String s) {
		if (s.contains("-")) { // if the word is hyphenated, split at the hyphen before counting syllables
			int count = 0;
			String[] temp = s.split("-");
			for (String t : temp) count+=countSyllables(t);
			return count;
		}

		int count = 0;
		Matcher m = syllable.matcher(s);
		while (m.find())
			count++;
		m = silentE.matcher(s);
		if (m.find()) count--;
		if (count <= 0) count = 1;
		return count;
	}

	@Override
	public double score(int[] stats) {
		double candScore = gradeLevel(stats[CAND_TOKEN_LEN],stats[CAND_SYLL_LEN]);
		double srcScore = gradeLevel(stats[SRC_TOKEN_LEN],stats[SRC_SYLL_LEN]);

		double penalty = 1.0;
		if (use_penalty == 1) {
			if (candScore > srcScore)
				//				penalty = 1.0 / Math.exp(srcScore - candScore); // THIS PENALTY GETS REEEEEALLY BIG
				penalty = Math.exp(candScore - srcScore);
			candScore *= penalty;
		}

		if (candScore > worstPossibleScore()) candScore = worstPossibleScore();
		if (candScore < bestPossibleScore()) candScore = bestPossibleScore();

		return candScore;
	}

	public static double gradeLevel(int numWords, int numSyllables) {
		return 0.39 * numWords + 11.8 * numSyllables/numWords - 15.19;
	}

	@Override
	public void printDetailedScore_fromStats(int[] stats, boolean oneLiner) {
		DecimalFormat df = new DecimalFormat("#.###");
		double candScore = gradeLevel(stats[CAND_TOKEN_LEN],stats[CAND_SYLL_LEN]);
		double srcScore = gradeLevel(stats[SRC_TOKEN_LEN],stats[SRC_SYLL_LEN]);
		double penalty = 1.0;
		if (use_penalty == 1) {
			if (candScore > srcScore)
				//				penalty = 1.0 / Math.exp(srcScore - candScore); // THIS PENALTY GETS REEEEEALLY BIG
				penalty = Math.exp(candScore - srcScore);
			candScore *= penalty;
		}

		System.out.print("GRADE_LEVEL = " + df.format(score(stats)));
		System.out.print(", REF_grade = "+df.format(gradeLevel(stats[REF_TOKEN_LEN],stats[REF_SYLL_LEN])));
		System.out.print(", CAND_grade = "+df.format(candScore));
		System.out.print(", SRC_grade = "+df.format(srcScore));
		System.out.println(", penalty = "+df.format(penalty));
		System.out.println();
	}
}
