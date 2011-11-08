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
	private Pattern wordPattern = Pattern.compile("[a-zA-Z]+");
	private double[] bleu_scores;
	private int CANDIDATE = 0, REFERENCE = 1, SOURCE = 2;
//	private final int CAND_TOKEN_LEN=0,CAND_WORD_LEN=1,CAND_SYLL_LEN=2,REF_TOKEN_LEN=3,REF_WORD_LEN=4,REF_SYLL_LEN=5,SRC_TOKEN_LEN=6,SRC_WORD_LEN=7,SRC_SYLL_LEN=8;

	public GradeLevelBLEU() {
		super();
		initialize();
	}
	public GradeLevelBLEU(String[] options) {
		// there are 3 arguments: the two for BLEU and one for the source sentences
		super(options);
		initialize();
		try {
			loadSources(options[2]);
		} catch (IOException e) {
			logger.severe("Error loading the source sentences from "+options[2]);
			System.exit(1);
		}

	}

	@Override
	public void initialize() {
		metricName = "GL_BLEU";
		toBeMinimized = false;
		suffStatsCount = 2*maxGramLength + 2; // the BLEU stats
		suffStatsCount += 6; // thje stats for here
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

		// first set the BLEU stats
	    if (!cand_str.equals("")) {
	    	String[] words = cand_str.split("\\s+");
	      set_prec_suffStats(stats,words,i);
	      stats[suffStatsCount-2-6] = words.length;
	      stats[suffStatsCount-1-6] = effLength(words.length,i);
	    } else {
	      String[] words = new String[0];
	      set_prec_suffStats(stats,words,i);
	      stats[suffStatsCount-2-6] = 0;
	      stats[suffStatsCount-1-6] = effLength(0,i);
	    }
	    
	    // now set the readability stats
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

		// syllable length
		stats[tokenLength(CANDIDATE)] = countTotalSyllables(candidate_tokens); ///candidate_words.length;
		stats[tokenLength(REFERENCE)] = countTotalSyllables(reference_tokens);;
		stats[tokenLength(SOURCE)] = countTotalSyllables(source_tokens); ///refSentences[i][sourceReferenceIndex].split("\\s+").length;

		// token length
		stats[syllableLength(CANDIDATE)] = candidate_tokens.length;
		stats[syllableLength(REFERENCE)] = reference_tokens.length;
		stats[syllableLength(SOURCE)] = source_tokens.length;

//		// number of words (note: != number tokens)
//		stats[wordCount(CANDIDATE)] = countWords(candidate_tokens);
//		stats[wordCount(REFERENCE)] = countWords(reference_tokens);
//		stats[wordCount(SOURCE)] = countWords(source_tokens);
		return stats;
	}
	
	private int tokenLength(int whichSentence) {
		return suffStatsCount - 3 - whichSentence;
	}
	private int syllableLength(int whichSentence) {
		return suffStatsCount - 3 - whichSentence;
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
//		double srcScore = gradeLevel(stats[tokenLength(SOURCE)],stats[syllableLength(SOURCE)]);
//		double grade_level_ratio = candScore / srcScore;

		double readabilityPenalty = 1;
		
		// to add a "readability penalty", set readabilityPenalty = < 1 if the candidate has a higher grade level than the source

//		double brevity_penalty = 1.0;
//		double c_len = stats[tokenLength(CANDIDATE)];
//		double r_len = stats[tokenLength(REFERENCE)];
//		if (c_len < r_len)
//			brevity_penalty = Math.exp(1 - (r_len / c_len));

		double combinedScore = (50*BLEUscore - candScore) * readabilityPenalty;
		if (combinedScore < worstPossibleScore()) combinedScore = worstPossibleScore();
		if (combinedScore > bestPossibleScore()) combinedScore = bestPossibleScore();
	
		return combinedScore;
//		return grade_level_ratio / brevity_penalty;
	}

	public double gradeLevel(int numWords, int numSyllables) {
		double d = 0.39 * numWords + 11.8 * numSyllables/numWords - 15.19;
//		System.out.println("\n"+d);
		return d;
	}

	@Override
	public void printDetailedScore_fromStats(int[] stats, boolean oneLiner) {
		DecimalFormat df = new DecimalFormat("#.###");
		System.out.print("GL_BLEU = " + df.format(score(stats)));
//		System.out.print(", READABILITY_peanlty = 1");
		System.out.print(", REF_GL = "+df.format(gradeLevel(stats[tokenLength(REFERENCE)],stats[syllableLength(REFERENCE)])));
		System.out.print(", CAND_GL = "+df.format(gradeLevel(stats[tokenLength(CANDIDATE)],stats[syllableLength(CANDIDATE)])));
		System.out.print(", SRC_GL = "+df.format(gradeLevel(stats[tokenLength(SOURCE)],stats[syllableLength(SOURCE)])));

		int[] BLEUstats = new int[suffStatsCount];
		for (int i = 0; i < suffStatsCount; i++) BLEUstats[i] = stats[i];
		System.out.print(", BLEU = "+df.format(super.score(stats)));
		System.out.println();
	}
}
