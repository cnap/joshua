package joshua.zmert;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GradeLevelBLEU extends BLEU {

	private static final Logger logger = Logger.getLogger(GradeLevelBLEU.class.getName());

	private String[] srcSentences;
	private Pattern syllable = Pattern.compile("(^[aeiouy]*[aeiouy]+)"); // matches C*V+
	private Pattern silentE = Pattern.compile("^[aeiou]e$");
	//	private Pattern wordPattern = Pattern.compile("[a-zA-Z]+");
	private int SOURCE = 0, CANDIDATE = 1, REFERENCE = 2;
	private boolean usePenalty = true;
//	double targetScore = 6.419; // tune.en avg GL = 6.419, tune.simp avg GL = 5.005
	double targetScore = 5.005; // tune.en avg GL = 6.419, tune.simp avg GL = 5.005
	private boolean useTarget = true;


	public GradeLevelBLEU() {
		super();
		initialize();
	}
	public GradeLevelBLEU(String[] options) {
		// there are 3 arguments: the two for BLEU and one for the source sentences
		super(options);
		if (Integer.parseInt(options[2]) > 0) targetScore = Integer.parseInt(options[2]);
		try {
			loadSources(options[3]);
		} catch (IOException e) {
			logger.severe("Error loading the source sentences from "+options[2]);
			System.exit(1);
		}
		//TODO set target GL
		initialize();
	}

	@Override
	public void initialize() {
		metricName = "GL_BLEU";
		toBeMinimized = false;
		suffStatsCount = 2*maxGramLength + 2 + 4; // the BLEU stats + 4
		set_weightsArray();
		set_maxNgramCounts();
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
	public double bestPossibleScore() { return 50; }
	@Override
	public double worstPossibleScore() { return 0; }

	@Override
	public int[] suffStats(String cand_str, int i) {
		int[] stats = new int[suffStatsCount];

		String[] candidate_tokens = null;
		// first set the BLEU stats
		if (!cand_str.equals("")) {
			candidate_tokens = cand_str.split("\\s+");
		} else {
			String[] words = new String[0];
			stats[tokenLength(CANDIDATE)] = 0;
			stats[tokenLength(REFERENCE)] = effLength(0,i);
		}
		set_prec_suffStats(stats,candidate_tokens,i);

		// now set the readability stats
		String [] reference_tokens = refSentences[i][0].split("\\s+");
		String [] source_tokens = srcSentences[i].split("\\s+");

		// drop "_OOV" marker
		for (int j = 0; j < candidate_tokens.length; j++) {
			if (candidate_tokens[j].endsWith("_OOV"))
				candidate_tokens[j] = candidate_tokens[j].substring(0, candidate_tokens[j].length() - 4);
		}

		// token length
		stats[tokenLength(CANDIDATE)] = candidate_tokens.length;
		stats[tokenLength(REFERENCE)] = reference_tokens.length;
		stats[tokenLength(SOURCE)] = source_tokens.length;

		// syllable length
		stats[syllableLength(CANDIDATE)] = countTotalSyllables(candidate_tokens); ///candidate_words.length;
		stats[syllableLength(REFERENCE)] = countTotalSyllables(reference_tokens);;
		stats[syllableLength(SOURCE)] = countTotalSyllables(source_tokens); ///refSentences[i][sourceReferenceIndex].split("\\s+").length;

		return stats;
	}

	private int tokenLength(int whichSentence) {
		return suffStatsCount - 3 + whichSentence;
	}
	private int syllableLength(int whichSentence) {
		return suffStatsCount - 6 + whichSentence;
	}

	public int countTotalSyllables(String[] ss) {
		int count = 0;
		for (String s : ss) count+=countSyllables(s);
		return count;
	}

	// add a syllable for punctuation, etc., so it isn't free
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
		if (count <= 0) count = 1;
		return count;
	}

	@Override
	public double score(int[] stats) {
		if (stats.length != suffStatsCount) {
			logger.severe("Mismatch between stats.length and suffStatsCount (" + stats.length + " vs. " + suffStatsCount + ") in BLEU.score(int[])");
			System.exit(2);
		}
		double BLEUscore = super.score(stats);
		double candScore = gradeLevel(stats[tokenLength(CANDIDATE)],stats[syllableLength(CANDIDATE)]);
		double srcScore = gradeLevel(stats[tokenLength(SOURCE)],stats[syllableLength(SOURCE)]);
		double readabilityPenalty = 1;
		if (usePenalty && candScore > srcScore) readabilityPenalty = Math.exp(srcScore-candScore);
		if (useTarget && candScore >= targetScore) readabilityPenalty = 0;
		//			readabilityPenalty  = candScore / srcScore;

		// to add a "readability penalty", set readabilityPenalty = < 1 if the candidate has a higher grade level than the source

		//		double brevity_penalty = 1.0;
		//		double c_len = stats[tokenLength(CANDIDATE)];
		//		double r_len = stats[tokenLength(REFERENCE)];
		//		if (c_len < r_len)
		//			brevity_penalty = Math.exp(1 - (r_len / c_len));

		//TODO: changed lambda to 100, may want to change back to 50
		double combinedScore = (100*BLEUscore - candScore) * readabilityPenalty;
		if (combinedScore < worstPossibleScore()) combinedScore = worstPossibleScore();
		if (combinedScore > bestPossibleScore()) combinedScore = bestPossibleScore();

		return combinedScore;
		//		return grade_level_ratio / brevity_penalty;
	}

	public double gradeLevel(int numWords, int numSyllables) {
		double d = 0.39 * numWords + 11.8 * numSyllables/numWords - 15.19;
		if (d < 0) d = 0;
		//		System.out.println("\n"+d);
		return d;
	}

	@Override
	public void printDetailedScore_fromStats(int[] stats, boolean oneLiner) {
		DecimalFormat df = new DecimalFormat("#.###");
		System.out.print("GL_BLEU = " + df.format(score(stats)));
		//		System.out.print(", READABILITY_peanlty = 1");
		double source_gl = gradeLevel(stats[tokenLength(SOURCE)],stats[syllableLength(SOURCE)]);
		double cand_gl = gradeLevel(stats[tokenLength(CANDIDATE)],stats[syllableLength(CANDIDATE)]);
		double penalty = 1;
		if (usePenalty && cand_gl > source_gl) penalty = Math.exp(source_gl - cand_gl);
		if (useTarget && cand_gl >= targetScore) penalty = 0;

		System.out.print("\tREF_GL = "+df.format(gradeLevel(stats[tokenLength(REFERENCE)],stats[syllableLength(REFERENCE)])));
		System.out.print("\tCAND_GL = "+df.format(cand_gl));
		System.out.print("\tSRC_GL = "+df.format(source_gl));
		System.out.print("\tBLEU = "+df.format(super.score(stats)));
		System.out.print("\tPenalty = "+df.format(penalty));
		System.out.println();
	}
}
