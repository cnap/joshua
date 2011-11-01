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

// The metric re-uses most of the CompressionBLEU/BLEU code
public class ReadabilityBLEU extends BLEU {
	private static final Logger	logger	= Logger.getLogger(ReadabilityBLEU.class.getName());

	// read source sentences into this array:
	private String[] srcSentences;
	private int[] srcWordCount;
	DecimalFormat df = new DecimalFormat("#.###");
	private String[][] refParses;
    private double targetScore;
	Pattern syllable = Pattern.compile("(^[aeiouy]*[aeiouy]+)"); // matches C*V+
	Pattern silentE = Pattern.compile("^[aeiou]e$");

	public ReadabilityBLEU() {
		super();
		initialize();
	}

	public ReadabilityBLEU(String[] options) {
		super(options);
		try {
			loadSources(options[2]);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(-1);
		}
		initialize();
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

	// in addition to BLEU's statistics, we store some length info;
	// for character-based compression we need to store more (for token-based
	// BLEU already has us partially covered by storing some num_of_words)
	//
	// here's where you'd make additional room for statistics of your own
	@Override
	protected void initialize() {
		metricName = "READ_BLEU";
		toBeMinimized = false;
		// adding 1 to the sufficient stats for regular BLEU - character-based compression requires extra stats
		suffStatsCount = 2 * maxGramLength + 6; // need to +3 for syllables
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
		//		System.out.println("1\t"+stats[src_char_len()]);
		//		System.out.println("2\t"+srcSentences[i].length());
		//		System.out.println("3\t"+srcWordCount[i]);
		//		System.out.println("4\t"+i);
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

	int cand_words_len() { return suffStatsCount - 6; }
	int ref_words_len() { return suffStatsCount - 5; } // effective = reference
	int ref_syl_len() { return suffStatsCount - 4; }
	int cand_syl_len() { return suffStatsCount - 3; }
	int src_syl_len() { return suffStatsCount - 2; }
	int src_words_len() { return suffStatsCount - 1; }

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

//		if (effLengthMethod == EffectiveLengthMethod.CLOSEST) {
//		}
//		if (effLengthMethod == EffectiveLengthMethod.SHORTEST){
			for (int r = 0; r < refsPerSen; r++) {
				//double thisRefTokLength = refWordCount[i][r];
				int thisRefSylLength = totalNumSyllables(refSentences[i][r].split("\\s+"));
				if (thisRefSylLength/refWordCount[i][r] < shortestAvgSylCount) {
					shortestAvgSylCount = thisRefSylLength/refWordCount[i][r];
					shortestTokCount = refWordCount[i][r];
				}
			}
//		}
		return shortestTokCount;
	}

	// hacked to be able to return character length upon request
	public int effLength(int candLength, int i) {
		if (effLengthMethod == EffectiveLengthMethod.CLOSEST) {
			int closestRefLength = Integer.MIN_VALUE;
			int minDiff = Math.abs(candLength - closestRefLength);

			for (int r = 0; r < refsPerSen; ++r) {
				int nextRefLength = refWordCount[i][r];
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
				int nextRefLength = refWordCount[i][r];
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
		double readability_penalty = getReadabilityPenalty(srcScore, candScore);

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
		double readability_penalty = getReadabilityPenalty(srcScore,candScore);
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
		// GRADE LEVEL:
		double d = 0.39*numWords + 11.8*numSyllables / numWords - 15.59;
		//READABILITY EASE:
		//double d = 206.835 - 1.015 * numWords - 84.6 * numSyllables / numWords;
		if (d < 0) return 0;
		return d;
	}

    double getReadabilityPenalty(double srcScore,double candScore) {
	// if the original sentence is MORE readable, return 0
	if (srcScore < candScore) return 0.0;

	// if the new sentence is more readable, return 1
	if ( srcScore > candScore) 
	    return 1.0;
	// if they are the same level of readability, return 0
	return 0;// candScore - srcScore;


	//		if (read_diff > 1.0) return 1.0;
		//return 0.0;
		//		System.err.println(fk_ratio);
		//		return Math.exp(fk_ratio-1);
	//	return Math.exp(read_diff);
		//TODO: not sure if this linear penalty is good enough
		//		return 0.0;
	}

    double getReadabilityPenalty(double srcScore,double candScore, boolean target) {
	if (!target) {
	    return getReadabilityPenalty(srcScore,candScore);
	}
	if (candScore > srcScore) return 0.0;
	if (candScore <= targetScore) return 1.0;
	return Math.exp(targetScore - candScore);
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
	    if (s.contains("-")) {
		    String[] temp = s.split("-");
		    int  count = 0;
		    for (String t : temp) {
			count+= numSyllables(t);
		    }
		    return count;
		}
		int count = 0;
		Matcher m = syllable.matcher(s);
		count = m.groupCount();
		m = silentE.matcher(s);
		if (m.find())
			count--;
		return count;
	}
}