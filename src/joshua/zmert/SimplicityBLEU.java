package joshua.zmert;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The metric re-uses most of the CompressionBLEU/BLEU code
public class SimplicityBLEU extends BLEU {
	private static final Logger	logger	= Logger.getLogger(SimplicityBLEU.class.getName());

	// read source sentences into this array:
	private String[] srcSentences;
	private int[] srcWordCount;
	DecimalFormat df = new DecimalFormat("#.###");
	private String[][] refParses;

	Pattern syllable = Pattern.compile("(^[aeiouy]*[aeiouy]+)"); // matches C*V+
	Pattern silentE = Pattern.compile("^[aeiou]e$");

	private String working_dir = null;
	private String src_path = null;
	private String nbest_text_path = null;
	private String nbest_parsed_path= null;
	private String be_path = null;
	private boolean nbest_preparsed = false;
	private final static HashSet<String> DET_LIST = new HashSet<String>(Arrays.asList("DT","PDT"));
	private final static HashSet<String> ADJ_LIST = new HashSet<String>(Arrays.asList("JJ","JJR","JJS"));
	private final static HashSet<String> NOUN_LIST = new HashSet<String>(Arrays.asList("NN","NNS","NP","NPS","PRP","FW"));
	private final static HashSet<String> ADV_LIST = new HashSet<String>(Arrays.asList("RB","RBR","RBS"));
	private final static HashSet<String> VERB_LIST = new HashSet<String>(Arrays.asList("VB","VBN","VBG","VBP","VBZ","MD"));
	private final static HashSet<String> WH_LIST = new HashSet<String>(Arrays.asList("WDT","WP","WP$","WRB"));
	private final static int OTHER=-2,WH=-1,DET=0,ADJ=1,NOUN=2,ADV=3,VERB=4;

	private static HashSet<String> BASIC_WORDS;

	public SimplicityBLEU() {
		super();
		initialize();
	}

	public SimplicityBLEU(String[] options) {
		super(options);
		try {
			loadConfigFile(options[2]);
			loadSources(options[2]);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(-1);
		}
		initialize();
	}

	private void loadConfigFile(String filepath) throws IOException {
		BufferedReader br = new BufferedReader(new FileReader(filepath));
		String line;
		String[] vals;
		while ( (line = br.readLine()) != null) {
			line = line.trim();
			if (line.equals("")) continue;
			vals = line.split("=");
			if (vals.length!= 2) {
				System.err.println("Illegally formatted config file; skipping line \""+line+"\"");
				continue;
			}
			if (vals[0].equals("working_dir"))
				working_dir = vals[1];
			else if (vals[0].equals("source_sentences"))
				src_path = vals[1];
			else if (vals[0].equals("nbest_text"))
				nbest_text_path = vals[1];
			else if (vals[0].equals("nbest_parsed"))
				nbest_parsed_path = vals[1];
			else if (vals[0].equals("nbest_preparsed")) {
				if (vals[1].equals("true"))
					nbest_preparsed = true;
			}
			else if (vals[0].equals("basic_english"))
				be_path=vals[1];
			else {
				System.err.println("Invalid option in config file; skipping "+vals[0]);
			}



		}

	}

	private void loadSources(String filepath) throws IOException {
		BufferedReader br = new BufferedReader(new FileReader(filepath));
		String line;
		srcSentences = new String[numSentences];
		int i = 0;
		while ( i < numSentences && (line=br.readLine()) != null ) {
			srcSentences[i] = line.trim();
			i++;
		}
		srcWordCount = new int[numSentences];
		for (i = 0; i < numSentences; i++) {
			srcWordCount[i] = wordCount(srcSentences[i]);
		}
	}

	private void loadParsedNbestList(String filepath) throws IOException {
		BufferedReader br = new BufferedReader(new FileReader(filepath));
		String line;
		refParses = new String[numSentences][refsPerSen];
		for (int i = 0; i < numSentences; i++) {
			for (int r = 0; r < refsPerSen; r++) {
				line = br.readLine();
				if (line == null) {
					System.err.println("not enough parses in "+filepath+"; exiting");
					System.exit(-1);
				}
				refParses[i][r] = line.trim();
			}
		}
	}

	private void loadBasicDictionary(String filepath) throws IOException {
		BufferedReader br = new BufferedReader(new FileReader(filepath));
		String line;
		BASIC_WORDS = new HashSet<String>();
		while ( (line = br.readLine()) != null) {
			BASIC_WORDS.add(line.trim());
		}
	}

	// in addition to BLEU's statistics, we store some length info;
	// for character-based compression we need to store more (for token-based
	// BLEU already has us partially covered by storing some num_of_words)
	//
	// here's where you'd make additional room for statistics of your own
	@Override
	protected void initialize() {
		metricName = "SIMP_BLEU";
		toBeMinimized = false;
		// adding 1 to the sufficient stats for regular BLEU - character-based compression requires extra stats
		suffStatsCount = 2 * maxGramLength + 9; // need to +3 for syllables
		set_weightsArray();
		set_maxNgramCounts();
	}

	//TODO ask juri
	// the only difference to BLEU here is that we're excluding the input from
	// the collection of ngram statistics - that's actually up for debate
	@Override
	protected void set_maxNgramCounts() {
		@SuppressWarnings("unchecked")
		HashMap<String, Integer>[] temp_HMA = new HashMap[numSentences];
		maxNgramCounts = temp_HMA;

		String gram = "";
		int oldCount = 0, nextCount = 0;

		for (int i = 0; i < numSentences; ++i) {
			// update counts as necessary from the reference translations
			for (int r = 0; r < refsPerSen; ++r) {
				// skip source reference
				if (maxNgramCounts[i] == null) {
					maxNgramCounts[i] = getNgramCountsAll(refSentences[i][r]);
				} else {
					HashMap<String, Integer> nextNgramCounts = getNgramCountsAll(refSentences[i][r]);
					Iterator<String> it = (nextNgramCounts.keySet()).iterator();

					while (it.hasNext()) {
						gram = it.next();
						nextCount = nextNgramCounts.get(gram);

						if (maxNgramCounts[i].containsKey(gram)) {
							oldCount = maxNgramCounts[i].get(gram);
							if (nextCount > oldCount) {
								maxNgramCounts[i].put(gram, nextCount);
							}
						} else { // add it
							maxNgramCounts[i].put(gram, nextCount);
						}
					}
				}
			} // for (r)
		} // for (i)

		// for efficiency, calculate the reference lenghts, which will be used
		// in effLength...
		refWordCount = new int[numSentences][refsPerSen];
		for (int i = 0; i < numSentences; ++i) {
			for (int r = 0; r < refsPerSen; ++r) {
				refWordCount[i][r] = wordCount(refSentences[i][r]);
			}
		}
	}

	// computation of statistics
	@Override
	public int[] suffStats(String cand_str, int i) {
		int[] stats = new int[suffStatsCount];

		String[] candidate_words;
		if (!cand_str.equals(""))
			candidate_words = cand_str.split("\\s+");
		else
			candidate_words = new String[0];

		// dropping "_OOV" marker
		for (int j = 0; j < candidate_words.length; j++) {
			if (candidate_words[j].endsWith("_OOV"))
				candidate_words[j] = candidate_words[j].substring(0, candidate_words[j].length() - 4);
		}

		// this collects all of the ngram prec stats
		set_prec_suffStats(stats, candidate_words, i);

		//------------
		// CHARACTER-BASED STATS
		// same as BLEU
		stats[cand_words_len()] = candidate_words.length;
		stats[ref_words_len()] = effLength(candidate_words.length, i);
		// candidate character length
		stats[cand_char_len()] = cand_str.length() - candidate_words.length + 1;
		// reference character length
		stats[ref_char_len()] = effLength(stats[cand_char_len()], i, true);
		// source character length
		stats[src_char_len()] = srcSentences[i].length() - srcWordCount[i] + 1;
		stats[src_words_len()] = srcWordCount[i];

		// candidate total num syllables
		stats[cand_syl_len()] = totalNumSyllables(candidate_words); ///candidate_words.length;
		// reference total num syllables
		stats[ref_syl_len()] = effSyllables(i);
		//		stats[ref_tok_len()] = effTokens(i);

		// source total num syllables
		stats[src_syl_len()] = totalNumSyllables(srcSentences[i].split("\\s+")); ///refSentences[i][sourceReferenceIndex].split("\\s+").length;
		return stats;
	}

	int cand_words_len() { return suffStatsCount - 9; }
	int ref_words_len() { return suffStatsCount - 8; } // effective = reference
	int cand_char_len() { return suffStatsCount - 7; }
	int ref_char_len() { return suffStatsCount - 6; }
	int src_char_len() { return suffStatsCount - 5; }
	int ref_syl_len() { return suffStatsCount - 4; }
	int cand_syl_len() { return suffStatsCount - 3; }
	int src_syl_len() { return suffStatsCount - 2; }
	//	int ref_tok_len() { return suffStatsCount - 1; }
	int src_words_len() { return suffStatsCount - 1; }

	// SIMPLIFICATION STATISTICS
	// 1.	POS tags (add higher order ngrams later)
	// 2. 	BasicEnglish words
	// 3.	longest NP
	// 4.	# NPs
	// 5.	# VPs
	// 6.	# PPs
	// 7.	Parse tree height
	// 8.	degree of right branching
	// 9.	type/token ratio of BE v. normal words
	// 10.	# subtrees to height
	//
	// The most predictive features for sentence classification were the ratio of different
	// tree non-terminals (VP, S, NP, S-Bar) to the number of words in the sentence, the ratio
	// of the total height of the productions in a tree to the height of the tree, and the
	// extent to which the tree was right branching

	public int reduceTag(String s) {
		s = s.toUpperCase();
		if (DET_LIST.contains(s)) return DET;
		if (ADJ_LIST.contains(s)) return ADJ;
		if (NOUN_LIST.contains(s)) return NOUN;
		if (VERB_LIST.contains(s)) return VERB;
		if (WH_LIST.contains(s)) return WH;
		if (ADV_LIST.contains(s)) return ADV;
		return OTHER;
	}


	@Override
	public int effLength(int candLength, int i) {
		return effLength(candLength, i, false);
	}

	public int effSyllables(int i) {
		int shortestTotSylCount = Integer.MAX_VALUE;

		double shortestAvgSylCount = Double.MAX_VALUE;

		//		if (effLengthMethod == EffectiveLengthMethod.CLOSEST) {
		//		}
		//		if (effLengthMethod == EffectiveLengthMethod.SHORTEST){
		for (int r = 0; r < refsPerSen; r++) {
			//double thisRefTokLength = refWordCount[i][r];
			//System.out.println("REF\t"+refSentences[i][r]);
			int thisRefSylLength = totalNumSyllables(refSentences[i][r].split("\\s+"));
			if (thisRefSylLength/refWordCount[i][r] < shortestAvgSylCount) {
				shortestAvgSylCount = thisRefSylLength/refWordCount[i][r];
				shortestTotSylCount = thisRefSylLength;
			}
		}
		//		}
		return shortestTotSylCount;
	}

	public int effTokens(int i) {
		int shortestTokCount = Integer.MAX_VALUE;

		double shortestAvgSylCount = Double.MAX_VALUE;

		if (effLengthMethod == EffectiveLengthMethod.CLOSEST) {
		}
		if (effLengthMethod == EffectiveLengthMethod.SHORTEST){
			for (int r = 0; r < refsPerSen; r++) {
				//double thisRefTokLength = refWordCount[i][r];
				int thisRefSylLength = totalNumSyllables(refSentences[i][r].split("\\s+"));
				if (thisRefSylLength/refWordCount[i][r] < shortestAvgSylCount) {
					shortestAvgSylCount = thisRefSylLength/refWordCount[i][r];
					shortestTokCount = refWordCount[i][r];
				}
			}
		}
		return shortestTokCount;
	}

	// hacked to be able to return character length upon request
	public int effLength(int candLength, int i, boolean character_length) {
		if (effLengthMethod == EffectiveLengthMethod.CLOSEST) {
			int closestRefLength = Integer.MIN_VALUE;
			int minDiff = Math.abs(candLength - closestRefLength);

			for (int r = 0; r < refsPerSen; ++r) {
				int nextRefLength = (character_length ? refSentences[i][r].length() - refWordCount[i][r] + 1 : refWordCount[i][r]);
				int nextDiff = Math.abs(candLength - nextRefLength);

				if (nextDiff < minDiff) {
					closestRefLength = nextRefLength;
					minDiff = nextDiff;
				} else if (nextDiff == minDiff && nextRefLength < closestRefLength) {
					closestRefLength = nextRefLength;
					minDiff = nextDiff;
				}
			}
			return closestRefLength;
		} else if (effLengthMethod == EffectiveLengthMethod.SHORTEST) {
			int shortestRefLength = Integer.MAX_VALUE;

			for (int r = 0; r < refsPerSen; ++r) {
				int nextRefLength = (character_length ? refSentences[i][r].length() - refWordCount[i][r] + 1 : refWordCount[i][r]);
				if (nextRefLength < shortestRefLength) {
					shortestRefLength = nextRefLength;
				}
			}
			return shortestRefLength;
		}

		return candLength; // should never get here anyway
	}

	// calculate the actual score from the statistics
	@Override
	public double score(int[] stats) {

		if (stats.length != suffStatsCount) {
			logger.severe("Mismatch between stats.length and " + "suffStatsCount (" + stats.length + " vs. " + suffStatsCount + ") in COMP_BLEU.score(int[])");
			System.exit(2);
		}

		double accuracy = 0.0;
		double smooth_addition = 1.0; // following bleu-1.04.pl
		double candScore = readabilityScore(stats[cand_words_len()],stats[cand_syl_len()]);
		double srcScore = readabilityScore(stats[src_words_len()],stats[src_syl_len()]);
		double fk_ratio = candScore / srcScore;

		double c_len = stats[cand_words_len()];
		double r_len = stats[ref_words_len()];
		double readability_penalty = getReadabilityPenalty(fk_ratio);

		// this part matches BLEU
		double correctGramCount, totalGramCount;
		for (int n = 1; n <= maxGramLength; ++n) {
			correctGramCount = stats[2 * (n - 1)];
			totalGramCount = stats[2 * (n - 1) + 1];

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
			accuracy += weights[n] * Math.log(prec_n);
		}
		double brevity_penalty = 1.0;

		if (c_len < r_len)
			brevity_penalty = Math.exp(1 - (r_len / c_len));

		// we tack on our penalty on top of BLEU
		//		System.out.println("CAND_score   = "+candScore);
		//		System.out.println("SRC_score    = "+srcScore);
		//		System.out.println("READ_penalty = " + readability_penalty);
		//		System.out.println("SIMP_BLEU    = " + readability_penalty*brevity_penalty*Math.exp(accuracy));
		return readability_penalty * brevity_penalty * Math.exp(accuracy);
	}

	// somewhat not-so-detailed, this is used in the JoshuaEval tool
	@Override
	public void printDetailedScore_fromStats(int[] stats, boolean oneLiner) {
		double candScore = readabilityScore(stats[cand_words_len()],stats[cand_syl_len()]);
		double srcScore = readabilityScore(stats[src_words_len()],stats[src_syl_len()]);
		double fk_ratio = candScore / srcScore;
		double readability_penalty = getReadabilityPenalty(fk_ratio);
		if (oneLiner) {
			System.out.print("SIMP_BLEU = " + df.format(score(stats)));
			System.out.print(", CAND_score = "+df.format(candScore));
			System.out.print(", SRC_score = "+df.format(srcScore));
			System.out.print(", READ_penalty = " + df.format(readability_penalty));
			System.out.println(", FK_ratio = "+df.format(candScore/srcScore));
		}
		else {
			System.out.println("SIMP_BLEU    = " + df.format(score(stats)));
			System.out.println("CAND_score   = "+df.format(candScore));
			System.out.println("SRC_score    = "+df.format(srcScore));
			System.out.println("READ_penalty = " + df.format(readability_penalty));
			System.out.println("FK_ratio     = "+df.format(candScore/srcScore));
		}
	}

	public double readabilityScore(int numWords, int numSyllables) {
		double d = 206.835 - 1.015 * numWords - 84.6 * numSyllables / numWords;
		if (d <= 0) return 0.000001;
		return d;
	}

	double getReadabilityPenalty(double fk_ratio) {
		if (fk_ratio > 1.0) {
			return 1.0;
		}
		return Math.exp(10 * (fk_ratio-1));
		//TODO: not sure if this linear penalty is good enough
		//		return 0.0;
	}

	public int totalNumSyllables(String[] ss) {
		int count = 0;
		for (String s : ss) {
			count+=numSyllables(s);
		}
		return count;
	}

	public int numSyllables(String s) {
		// for numbers, return number of digits. this may not be good!
		//		if (! s.matches("[a-z]"))
		//			return s.length();
		int count = 0;
		Matcher m = syllable.matcher(s);
		count = m.groupCount();
		m = silentE.matcher(s);
		if (m.find())
			count--;
		return count;
	}
}