package joshua.zmert;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GradeLevelMetric extends EvaluationMetric {

	private String[] srcSentences;
	private Pattern syllable = Pattern.compile("(^[aeiouy]*[aeiouy]+)"); // matches C*V+
	private Pattern silentE = Pattern.compile("^[aeiou]e$");
	private Pattern wordPattern = Pattern.compile("[a-zA-Z]+");
	private double[] bleu_scores;
	private final int CAND_TOKEN_LEN=0,CAND_WORD_LEN=1,CAND_SYLL_LEN=2,REF_TOKEN_LEN=3,REF_WORD_LEN=4,REF_SYLL_LEN=5,SRC_TOKEN_LEN=6,SRC_WORD_LEN=7,SRC_SYLL_LEN=8;

	public GradeLevelMetric() {
		initialize();
	}
	public GradeLevelMetric(String[] options) {
		initialize();
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
		suffStatsCount = 9;
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
		stats[REF_SYLL_LEN] = countTotalSyllables(reference_tokens);;
		stats[SRC_SYLL_LEN] = countTotalSyllables(source_tokens); ///refSentences[i][sourceReferenceIndex].split("\\s+").length;

		// number of words (note: != number tokens)
		stats[CAND_WORD_LEN] = countWords(candidate_tokens);
		stats[REF_WORD_LEN] = countWords(reference_tokens);
		stats[SRC_WORD_LEN] = countWords(source_tokens);
		return stats;
	}

	private int countWords(String[] tokens) {
		int i = 0;
		for (String t : tokens) {
			Matcher m = wordPattern.matcher(t);
			if (m.find()) i++;
		}
		return i;
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
		count = m.groupCount();
		m = silentE.matcher(s);
		if (m.find()) count--;
		return count;
	}

	@Override
	public double score(int[] stats) {
		double candScore = gradeLevel(stats[CAND_WORD_LEN],stats[CAND_SYLL_LEN]);
		double srcScore = gradeLevel(stats[SRC_WORD_LEN],stats[SRC_SYLL_LEN]);
		double grade_level_ratio = candScore / srcScore;

		double c_len = stats[CAND_TOKEN_LEN];
		double r_len = stats[REF_TOKEN_LEN];

		double brevity_penalty = 1.0;

		if (c_len < r_len)
			brevity_penalty = Math.exp(1 - (r_len / c_len));

		return grade_level_ratio / brevity_penalty;
	}

	public double gradeLevel(int numWords, int numSyllables) {
		return 0.39 * numWords + 11.8 * numSyllables/numWords - 15.19;
	}

	@Override
	public void printDetailedScore_fromStats(int[] stats, boolean oneLiner) {
		DecimalFormat df = new DecimalFormat("#.###");
		System.out.print("GRADE_LEVEL_RATIO = " + df.format(score(stats)));
		System.out.print(", REF_grade = "+df.format(gradeLevel(stats[REF_WORD_LEN],stats[REF_SYLL_LEN])));
		System.out.print(", CAND_grade = "+df.format(gradeLevel(stats[CAND_WORD_LEN],stats[CAND_SYLL_LEN])));
		System.out.print(", SRC_grade = "+df.format(gradeLevel(stats[SRC_WORD_LEN],stats[SRC_SYLL_LEN])));
		System.out.println();
	}
}
