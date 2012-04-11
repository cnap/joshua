package joshua.zmert;


import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GradeLevelBLEU extends BLEU {

	private static final Logger logger = Logger.getLogger(GradeLevelBLEU.class.getName());

	private String[] srcSentences;
	private Pattern syllable = Pattern.compile("([^aeiouy]*[aeiouy]+)"); // matches
																			// C*V+
	private Pattern silentE = Pattern.compile("[^aeiou]e$");
	//	private Pattern wordPattern = Pattern.compile("[a-zA-Z]+");
	private int SOURCE = 0, CANDIDATE = 1, REFERENCE = 2;
	double targetGL = 9.87; // tune.en avg GL = 14.0785, tune.simp avg GL = 9.8704
	private boolean useTarget = true;
	private boolean useExpPenalty = false;
	private boolean useBLEUplus = true;
	private double alpha = 0.9;
	HashMap<String, Integer>[] srcMaxNgramCounts;

	public GradeLevelBLEU() {
		super();
	}

	// target <= 0 indicates use the default target
	// target > 0 indicates use that target
	public GradeLevelBLEU(String[] options) {
		super();
		// there are 4 arguments: the two for BLEU and one for the source sentences
		if (Double.parseDouble(options[0]) > 0)
			targetGL = Double.parseDouble(options[0]);
		if (Double.parseDouble(options[1]) > 0)
			alpha = Double.parseDouble(options[1]);
		try {
			loadSources(options[2]);
		} catch (IOException e) {
			logger.severe("Error loading the source sentences from "+options[2]);
			System.exit(1);
		}
		initialize();
		setSrcMaxNgramCounts();
	}

	public void initialize() {
		metricName = "GL_BLEU";
		effLengthMethod = EffectiveLengthMethod.SHORTEST;
		toBeMinimized = false;
		suffStatsCount = 4 * maxGramLength + 2 + 4; // the BLEU stats + 4
		set_weightsArray();
		set_maxNgramCounts();
	}

	public void setSrcMaxNgramCounts() {
		@SuppressWarnings("unchecked")
		HashMap<String, Integer>[] temp_HMA = new HashMap[numSentences];
		srcMaxNgramCounts = temp_HMA;

		for (int i = 0; i < numSentences; ++i) {
			srcMaxNgramCounts[i] = getNgramCountsAll(srcSentences[i]);
		}
	}

	public void loadSources(String filepath) throws IOException {
		BufferedReader br = new BufferedReader(new FileReader(filepath));
		String line;

		srcSentences = new String[numSentences];
		int i = 0;
		while ( i < numSentences && (line=br.readLine()) != null ) {
			srcSentences[i] = line.trim();
			i++;
		}
	}

	public double bestPossibleScore() { return 50; }
	public double worstPossibleScore() { return 0; }

	public double BLEU(int[] stats, int comparison) {
		if (comparison == REFERENCE)
			return super.score(stats);
		else if (comparison == SOURCE) {
			return srcBLEU(stats);
		} else
			return 1.0;
	}

	private double srcBLEU(int[] stats) {
		if (stats.length != suffStatsCount) {
			logger.severe("Mismatch between stats.length and suffStatsCount (" + stats.length + " vs. " + suffStatsCount + ") in BLEU.score(int[])");
			System.exit(2);
		}

		double BLEUsum = 0.0;
		double smooth_addition = 1.0; // following bleu-1.04.pl
		double c_len = stats[tokenLength(CANDIDATE)];
		double r_len = stats[tokenLength(SOURCE)];
		
		double correctGramCount, totalGramCount;

		for (int n = 1; n <= maxGramLength; ++n) {
			correctGramCount = stats[2 * maxGramLength + 2 * (n - 1)];
			totalGramCount = stats[2 * maxGramLength + 2 * (n - 1) + 1];

			double prec_n;
			if (totalGramCount > 0) {
				prec_n = correctGramCount / totalGramCount;
			} else {
				prec_n = 1; // following bleu-1.04.pl ???????
			}

			if (prec_n == 0) {
				smooth_addition *= 0.5;
				prec_n = smooth_addition / (c_len - n + 1);
				// isn't c_len-n+1 just totalGramCount ???????
			}

			BLEUsum += weights[n] * Math.log(prec_n);
		}

		double BP = 1.0;
		if (c_len < r_len)
			BP = Math.exp(1 - (r_len / c_len));
		// if c_len > r_len, no penalty applies

		return BP * Math.exp(BLEUsum);

	}

	public void set_src_prec_suffStats(int[] stats, String[] words, int i) {
		HashMap<String, Integer>[] candCountsArray = getNgramCountsArray(words);

		for (int n = 1; n <= maxGramLength; ++n) {

			int correctGramCount = 0;
			String gram = "";
			int candGramCount = 0, maxSrcGramCount = 0, clippedCount = 0;

			Iterator<String> it = (candCountsArray[n].keySet()).iterator();

			while (it.hasNext()) {
				// for each n-gram type in the candidate
				gram = it.next();
				candGramCount = candCountsArray[n].get(gram);
				// if (maxNgramCounts[i][n].containsKey(gram)) {
				// maxRefGramCount = maxNgramCounts[i][n].get(gram);
				if (srcMaxNgramCounts[i].containsKey(gram)) {
					maxSrcGramCount = srcMaxNgramCounts[i].get(gram);
				} else {
					maxSrcGramCount = 0;
				}

				clippedCount = Math.min(candGramCount, maxSrcGramCount);

				correctGramCount += clippedCount;

			}

			stats[2 * maxGramLength + 2 * (n - 1)] = correctGramCount;
			stats[2 * maxGramLength + 2 * (n - 1) + 1] = Math.max(words.length - (n - 1), 0); // total
																			// gram
																			// count

		} // for (n)

	}

	public int[] suffStats(String cand_str, int i) {
		int[] stats = new int[suffStatsCount];

		String[] candidate_tokens = null;
		// first set the BLEU stats
		if (!cand_str.equals("")) {
			candidate_tokens = cand_str.split("\\s+");
		} else {
			candidate_tokens = new String[0];
			stats[tokenLength(CANDIDATE)] = 0;
			stats[tokenLength(REFERENCE)] = effLength(0,i);
		}
		set_prec_suffStats(stats,candidate_tokens,i);
		set_src_prec_suffStats(stats, candidate_tokens, i);

		// now set the readability stats
		String [] reference_tokens = refSentences[i][0].split("\\s+");
		String[] source_tokens = srcSentences[i].split("\\s+");

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

		Double glc = gradeLevel(stats[tokenLength(CANDIDATE)], stats[syllableLength(CANDIDATE)]);
		Double gls = gradeLevel(stats[tokenLength(SOURCE)], stats[syllableLength(SOURCE)]);
		Double glr = gradeLevel(stats[tokenLength(REFERENCE)], stats[syllableLength(REFERENCE)]);
		//System.err.println(stats[tokenLength(CANDIDATE)]+"\t"+stats[syllableLength(CANDIDATE)]+"\t"+glc+"\t"+cand_str);
		//System.err.println(stats[tokenLength(SOURCE)] + "\t" + stats[syllableLength(SOURCE)] + "\t" + gls + "\t" + srcSentences[i]);
		//System.err.println(stats[tokenLength(REFERENCE)] + "\t" + stats[syllableLength(REFERENCE)] + "\t" + glr + "\t" + refSentences[i][0]);

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
		for (String s : ss) {
			int i = countSyllables(s);
			count += i;
		}
		return count;
	}

	// add a syllable for punctuation, etc., so it isn't free
	public int countSyllables(String s) {
		if (s.equals("-")) {
			return 1;
		}
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

	public double score(int[] stats) {
		if (stats.length != suffStatsCount) {
			logger.severe("Mismatch between stats.length and suffStatsCount (" + stats.length + " vs. " + suffStatsCount + ") in BLEU.score(int[])");
			System.exit(2);
		}
		double BLEUscore = BLEU(stats, REFERENCE);
		double candGL = gradeLevel(stats[tokenLength(CANDIDATE)],stats[syllableLength(CANDIDATE)]);
		double srcGL = gradeLevel(stats[tokenLength(SOURCE)],stats[syllableLength(SOURCE)]);

		double readabilityPenalty = 1;
		if (useTarget) {
			readabilityPenalty = getReadabilityPenalty(candGL,targetGL);
		}
		else readabilityPenalty = getReadabilityPenalty(candGL,srcGL);

		// if (usePenalty && candScore > srcScore) readabilityPenalty =
		// Math.exp(srcScore-candScore);
		// if (useTarget && candScore >= targetScore) readabilityPenalty = 0;
		//			readabilityPenalty  = candScore / srcScore;

		// to add a "readability penalty", set readabilityPenalty = < 1 if the candidate has a higher grade level than the source

		// if (useLinearPenalty) {
		// // TODO: changed lambda to 100, may want to change back to 50
		// double combinedScore = (100 * BLEUscore - candGL)
		// * readabilityPenalty;
		// if (combinedScore < worstPossibleScore())
		// combinedScore = worstPossibleScore();
		// if (combinedScore > bestPossibleScore())
		// combinedScore = bestPossibleScore();
		// return combinedScore;
		// }
		if (useBLEUplus) {
			double srcBLEUscore = BLEU(stats, SOURCE);
			// logger.warning("BLEU=" + BLEUscore + ",srcBLEU=" + srcBLEUscore +
			// ",a=" + alpha);
			BLEUscore = BLEU_plus(BLEUscore,srcBLEUscore);
		}
		return readabilityPenalty * BLEUscore;
	}


    private double BLEU_plus(double bleu_ref,double bleu_src) {
	return alpha * bleu_ref - (1-alpha)*bleu_src;
    }

	private double getReadabilityPenalty(double this_gl, double target_gl) {
		if (this_gl < target_gl) {
			return 1.0;
		}
		if (useExpPenalty) {
			return Math.exp(target_gl - this_gl);
		}
		return 0.0;
	}

	public double gradeLevel(int numWords, int numSyllables) {
		double d = 0.39 * numWords + 11.8 * numSyllables/numWords - 15.19;
		if (d < 0) d = 0;
		return d;
	}

	public void printDetailedScore_fromStats(int[] stats, boolean oneLiner) {
		DecimalFormat df = new DecimalFormat("#.###");
		System.out.print(df.format(score(stats)));
		double source_gl = gradeLevel(stats[tokenLength(SOURCE)],stats[syllableLength(SOURCE)]);
		double cand_gl = gradeLevel(stats[tokenLength(CANDIDATE)],stats[syllableLength(CANDIDATE)]);
		double penalty = 1;
		if (useTarget)
			penalty = getReadabilityPenalty(cand_gl, targetGL);
		else
			penalty = getReadabilityPenalty(cand_gl, source_gl);

		double bleu_ref = BLEU(stats, REFERENCE);
		double bleu_src = BLEU(stats, SOURCE);
		double bleu_plus = BLEU_plus(bleu_ref, bleu_src);

		System.out.print("\t" + df.format(gradeLevel(stats[tokenLength(REFERENCE)], stats[syllableLength(REFERENCE)])));
		System.out.print("\t" + df.format(cand_gl));
		System.out.print("\t" + df.format(source_gl));
		System.out.print("\t" + df.format(penalty));
		System.out.print("\t" + df.format(bleu_ref));
		System.out.print("\t" + df.format(bleu_src));
		System.out.print("\t" + df.format(bleu_plus));
		System.out.print("\t"+stats[tokenLength(CANDIDATE)]);
		System.out.println("\t"+stats[syllableLength(CANDIDATE)]);
	}

	public static String getOutputHeader() {
		return "SENT#\tGLB\tREF_GL\tCAND_GL\tSRC_GL\tPenalty\tBLEU(pp,ref)\tBLEU(pp,src)\tBLEU+\tTOK_LEN\tSYLL_LEN";
	}
}
