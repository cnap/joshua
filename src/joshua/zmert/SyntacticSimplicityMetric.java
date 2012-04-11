package joshua.zmert;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import org.tartarus.snowball.SnowballStemmer;
import org.tartarus.snowball.ext.englishStemmer;

import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreePrint;

public class SyntacticSimplicityMetric extends BLEU {
	private int[][] srcParseStats;
	private boolean[] srcStatsLoaded;
	private final HashSet<String> PHRASE_LABELS = new HashSet<String>(Arrays.asList("ADJP,ADVP,CONJP,INTJ,NP,PP,PRN,QP,RRC,UCP,VP,WHADJP,WHAVP,WHNP,WHPP".split(",")));
	private final HashSet<String> CLAUSE_LABELS = new HashSet<String>(Arrays.asList("S,SBAR,SBARQ,SINV,SQ".split(",")));
	private static HashMap<String,String> stems;
	private static HashSet<String> BASIC_WORDS;
	private static SnowballStemmer stemmer;
	private static HashMap<String,Integer> wordFrequencies;
	private Pattern syllable = Pattern.compile("([^aeiouy]*[aeiouy]+)"); // matches
																			// C*V+
	private Pattern silentE = Pattern.compile("[^aeiou]e$");
	List<Integer> frequencies;
	private final int LOWEST_FREQUENCY = 75000;
	String pathToParses;
	HashMap<String,Integer> ntCounts;
	LexicalizedParser parser;
	TreePrint constituentTreePrinter;
	Runtime r;
	FileWriter parseWriter;
	private String[] srcSentences;
	private String sourceFilename = null;
	HashMap<String,String> parseMap;
	double target = 7.02566633571197;
	boolean useTarget = true;
	// avg weighted score for tune.simp = 7.02566633571197
	// avg weighted score for tune.verified.en = 6.30404221285107
	public HashMap<String, Double> parseScores;
	
	// max ~11.18
	final double[] weight = {
			3.69502835452864,
			1.26044509901525,
			0.477196468625433,
			1.23174218570469,
			0.159968071686012,
			0.711246454431046,
			0.0386657528996089,
			1.93191587271441,
			0.447413473942941,
			1.23089145652181, };
	final int NUM_FEATURES = 10;

	//	flags for normalization:
	final int HEIGHT_RATIO = -1, VP_NP_RATIO = -2, NORM_GRADE_LEVEL = -3, NORM_MEDIAN = -4, NORM_RIGHT_BRANCH = -5;
	final int FEATURE_COUNT = 14;
	// features (14 total):
	final int TOKEN_LEN = 0, SYLL_LEN = 1, HEIGHT = 2, TOTAL_HEIGHT = 3,NUM_PHRASES = 4, NUM_NPS = 5, NUM_VPS = 6, NUM_PPS = 7, NUM_APS = 8, NUM_BASIC_WORDS = 9, NUM_WORDS = 10, NUM_CLAUSES = 11, MEDIAN = 12, RIGHT_BRANCH = 13;
	private int use_penalty = 1;
	private boolean useLinearPenalty = false;

	public SyntacticSimplicityMetric() {
		super();
		initialize();
	}

	// target = 0 indicates use the default target
	// target < -1 indicates use each source sentence as a target
	// target > 0 indicates use that target
	public SyntacticSimplicityMetric(String[] options) {
		super(options);
		if (Double.parseDouble(options[2]) > 0) target = Double.parseDouble(options[2]);
		else if (Double.parseDouble(options[2]) < 0) useTarget = false;
		if (options[3].toLowerCase().startsWith("lin"))
			useLinearPenalty = true;
		sourceFilename = options[4];
		try {
		    loadSources();
		} catch (Exception e) { System.err.println("Error loading sources from sourceFilename"); System.exit(1); }
		initialize();
		try {
			initializeParser();
		} catch (Exception e) {
			System.err.println("Error initializing parser");
			System.exit(1);
		}
	}

	public void initialize() {
		stems = new HashMap<String,String>();
		stemmer = new englishStemmer();

		try {
		    loadBasicWords();
			loadWordFrequencies();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}

		srcParseStats = new int[numSentences][FEATURE_COUNT];
		srcStatsLoaded = new boolean[numSentences];
		// these will be loaded when suffstats are calculated, so the boolean
		// flags keep track of what's been calculated to avoid redundancies
		// i think.

		// initialize ngram stats for bleu
		set_weightsArray();
		set_maxNgramCounts();

		metricName = "SYN_SIMP";
		toBeMinimized = false;
		// ngram features + simpl features + 2 (lengths for BLEU, these features will be present twice, oh well)
		suffStatsCount = 2*maxGramLength + FEATURE_COUNT * 2 + 2;
	}

	public void loadSources() throws IOException {
		BufferedReader br = new BufferedReader(new FileReader(sourceFilename));
		String line;
		srcSentences= new String[numSentences];
		int i = 0;
		while ( i < numSentences && (line=br.readLine()) != null ) {
			srcSentences[i] = line.trim();
			i++;
		}
		System.err.println("Loaded " + srcSentences.length + " sources");
	}

	private void initializeParser() throws Exception {
		r = Runtime.getRuntime();
		//		parser = new
		constituentTreePrinter = new TreePrint("oneline");
		parser = new LexicalizedParser(this.getClass().getResource("/joshua/zmert/resources/englishPCFG.ser.gz").getPath());
		loadExistingParses();
	}

	// this loads parses from te user's working dir
	//TODO fix this so user can specify dir or something
	private void loadExistingParses() throws Exception {
		pathToParses = System.getProperty("user.dir")+"/parsed_nbest_list";
		File f = new File(pathToParses);
		parseMap = new HashMap<String,String>();
		parseScores = new HashMap<String, Double>();
		if (!f.createNewFile()) {
			BufferedReader br = new BufferedReader(new FileReader(pathToParses));
			String line;
			String[] fields;
			while ( (line=br.readLine()) != null ) {
				fields = line.split("\\t");
				if (fields.length != 3) {
					continue;
				}
				//				fields[0] = normalizeSpacing(fields[0]);
				parseMap.put(fields[0],fields[1]);
				parseScores.put(fields[0], Double.parseDouble(fields[2]));
			}
		}
		System.err.println("Loaded "+parseMap.size()+" existing parses from "+pathToParses);
		parseWriter = new FileWriter(pathToParses,true);
	}

	public String normalizeSpacing(String s) { return s.trim().replaceAll("\\s+"," "); }

	// get the parse of a sentence and parse/print if not pre-parsed
	public Tree parse(String s) {
		if (parseMap.containsKey(s)) {
			Tree t = Tree.valueOf(parseMap.get(s));
			// check to see if this is an incomplete parse
			if (t != null)
				return t;
		}
		s = s.replaceAll("\\s+", " ");
		s = s.trim();
		Tree t = parser.apply(s);
		StringWriter treeStrWriter = new StringWriter();
		constituentTreePrinter.printTree(t, new PrintWriter(treeStrWriter, true));
		String parseString = treeStrWriter.toString().trim();
		try {
			parseWriter.write(s + "\t" + parseString + "\t" + t.score() + "\n");
		} catch (IOException e) {
			System.err.println("Error writing parse to "+pathToParses);
			e.printStackTrace();
		}
		parseMap.put(s,parseString);
		parseScores.put(s, t.score());

		return t;
	}

	private void loadBasicWords() throws IOException {
		BASIC_WORDS = new HashSet<String>();
		InputStream in = this.getClass().getResourceAsStream("/joshua/zmert/resources/be850.txt");
		if (in == null) {
			System.err.println("Failed to load resource be850.txt");
			System.exit(1);
		}

		BufferedReader br = new BufferedReader(new InputStreamReader(in));
		String line;
		while ( (line = br.readLine()) != null) {
			BASIC_WORDS.add(getStem(line));
		}
		br.close();
	}

	private void loadWordFrequencies() throws IOException {
		wordFrequencies = new HashMap<String,Integer>();
		InputStream in = this.getClass().getResourceAsStream("/joshua/zmert/resources/word_frequencies.txt");
		if (in == null) {
			System.err.println("Failed to load resource word_frequencies.txt");
			System.exit(1);
		}

		BufferedReader br = new BufferedReader(new InputStreamReader(in));
		String line;
		int count = 0;
		while ( (line = br.readLine()) != null) {
			wordFrequencies.put(line.trim(),count++);
		}
		br.close();
	}

	private String getStem(String s) {
		s = s.trim();
		if (!stems.containsKey(s)) {
			stemmer.setCurrent(s);
			stemmer.stem();
			stems.put(s,stemmer.getCurrent());
		}
		return stems.get(s);
	}

	//TODO determine new max/min scores
	public double bestPossibleScore() {
		double d = 0;
		for (int i = 0; i < weight.length; i++)
			d+= weight[i];
		return d;
	}

	//TODO determine new max/min scores
	public double worstPossibleScore() {
		return 0;
	}

	//normalize weights based on empirical data
	private double normalize(double d, int feature) {
		double norm = d;
		if (feature == HEIGHT_RATIO) {
			norm *= 5;
		}
		else if (feature == VP_NP_RATIO) {
			//	    norm = 1 - norm; // found in the svm process this should be 1 - (1 - norm)
		}
		else if (feature == NORM_GRADE_LEVEL) {
			if (norm > 50) norm = 50;
			norm = 1 - norm / 50;
		}
		else if (feature == NORM_RIGHT_BRANCH) {
			norm = 4.0 / norm;
		}
		else if (feature == NORM_MEDIAN) {
			norm = Math.pow(1.0 - 1.0*norm / 60000,50);
			//	    System.err.println(d);
		}
		if (norm > 1) norm = 1;
		if (norm < 0) norm = 0;
		return norm;
	}

	public int[] suffStats(String cand_str, int i) {
		int[] stats = new int[suffStatsCount];
		super.suffStats(cand_str, i);
//		try {
//			if (i == numSentences - 1) {
//				parseWriter.close();
//			}
//		} catch(Exception e) {
//			System.err.println("Error closing the file writer for "+ pathToParses);
//		}

		int[] temp_stats = 	calculateStatistics(cand_str);
		for (int j = 0; j < FEATURE_COUNT; j++) {
			stats[j+maxGramLength] = temp_stats[j];
		}
		if (!srcStatsLoaded[i]) {
			temp_stats = calculateStatistics(srcSentences[i]);
			for (int j = 0; j < FEATURE_COUNT; j++) {
				srcParseStats[i][j] = temp_stats[j];
			}
			srcStatsLoaded[i] = true;
		}
		for (int j = 0; j < FEATURE_COUNT; j++) {
			stats[j+FEATURE_COUNT+maxGramLength] = srcParseStats[i][j];
		}
		return stats;
	}

	public int[] calculateStatistics(String cand_str) {
		cand_str = normalizeSpacing(cand_str);
		Tree tree = parse(cand_str);

		int[] stats = new int[FEATURE_COUNT];
		String[] candidate_tokens;
		if (!cand_str.equals("")) candidate_tokens = cand_str.split("\\s+");
		else candidate_tokens = new String[0];

		// drop "_OOV" marker
		for (int j = 0; j < candidate_tokens.length; j++) {
			if (candidate_tokens[j].endsWith("_OOV"))
				candidate_tokens[j] = candidate_tokens[j].substring(0, candidate_tokens[j].length() - 4);
		}

		stats[TOKEN_LEN] = candidate_tokens.length;
		stats[SYLL_LEN] = countTotalSyllables(candidate_tokens);
		ntCounts = new HashMap<String, Integer>();
		int height = tree.depth();
		int total_height = 0;
		int num_phrases = 0;

		for (Tree production : tree.subTreeList()) {
			total_height+= production.depth();
			if (production.isPhrasal()) num_phrases++;
			if (!production.isLeaf()) {
				String label = production.label().value();
				if (!ntCounts.containsKey(label)) ntCounts.put(label,0);
				ntCounts.put(label, ntCounts.get(label)+1);
			}
		}
		stats[HEIGHT] = height;
		stats[TOTAL_HEIGHT] = total_height;
		stats[NUM_PHRASES] = num_phrases;
		int num_clauses = 0;
		int num_NPs = 0, num_VPs = 0, num_PPs = 0, num_APs = 0;
		for (String nt : ntCounts.keySet()) {
			if (CLAUSE_LABELS.contains(nt))
				num_clauses+=ntCounts.get(nt);
			else if (PHRASE_LABELS.contains(nt)) {
				if (nt.equals("NP")) num_NPs+=ntCounts.get(nt);
				if (nt.equals("VP")) {
					num_VPs+=ntCounts.get(nt);
				}
				if (nt.equals("PP")) num_PPs+=ntCounts.get(nt);
				if (nt.charAt(0) == 'A') num_APs+=ntCounts.get(nt);
			}
		}
		if (num_NPs <= 0) num_NPs = 1;
		stats[NUM_NPS] = num_NPs;
		stats[NUM_VPS] = num_VPs;
		List<Tree> leaves = tree.getLeaves();
		int j = tree.getLeaves().size()-1;
		while (!Pattern.matches("\\w+",leaves.get(j).value())) j--;
		int right_branch = tree.depth(leaves.get(j));
		stats[RIGHT_BRANCH] = right_branch;
		frequencies = new ArrayList<Integer>();

		int num_basic_words = 0;
		int num_words = 0;
		for (Tree leaf : leaves) {
			String word = leaf.value();
			if (!Pattern.matches("\\w+", word)) continue;

			if (wordFrequencies.containsKey(word)) frequencies.add(wordFrequencies.get(word));
			else frequencies.add(LOWEST_FREQUENCY);
			String stem = getStem(word);
			if (BASIC_WORDS.contains(stem)) num_basic_words++;
			num_words++;
		}
		stats[NUM_BASIC_WORDS] = num_basic_words;
		stats[NUM_WORDS] = num_words;
		stats[MEDIAN] = (int) median(frequencies);
		stats[NUM_CLAUSES] = num_clauses;
		stats[NUM_PPS] = num_PPs;
		stats[NUM_APS] = num_APs;

		return stats;
	}

	public double median(List<Integer> values) {
		Collections.sort(values);
		if (values.size() % 2 == 1)
			return values.get((values.size()+1)/2-1);
		return ( values.get(values.size()/2-1) + values.get(values.size()/2) ) / 2;
	}

	// source = 0 if scoring the candidate, = 1 if scoring the source sentence
	public double[] getScores(int[] stats, int source) {
		int start = source*FEATURE_COUNT+maxGramLength;
		double[] scores = new double[10];
		scores[0] = normalize(1.0 * stats[HEIGHT+start] / stats[TOTAL_HEIGHT+start], HEIGHT_RATIO);
		scores[1] = normalize(1.0 * stats[NUM_PHRASES+start] / stats[TOKEN_LEN+start], 0);
		scores[2] = normalize(1.0 * stats[NUM_VPS+start] / stats[NUM_NPS+start], VP_NP_RATIO);
		scores[3] = normalize(stats[RIGHT_BRANCH+start], NORM_RIGHT_BRANCH);
		scores[4] = 1.0 * stats[NUM_BASIC_WORDS+start] / stats[NUM_WORDS+start];
		scores[5] = normalize(stats[MEDIAN+start], NORM_MEDIAN);
		scores[6] = normalize(1.0 * stats[TOKEN_LEN+start] / stats[SYLL_LEN+start], 0);
		scores[7] = normalize(gradeLevel(stats[TOKEN_LEN+start], stats[SYLL_LEN+start]), NORM_GRADE_LEVEL);
		scores[8] = normalize(1.0 * stats[NUM_APS+start] / stats[NUM_NPS+start], VP_NP_RATIO);
		scores[9] = normalize(1.0 * stats[NUM_PPS+start] / stats[NUM_NPS+start], VP_NP_RATIO);
		return scores;
	}

	public double getRelativeScore(int[] stats) {
		double[] scores = getScores(stats, 0);
		double[] sourceScores = getScores(stats, 1);
		double score = 0;
		for (int i = 0; i < NUM_FEATURES; i++) {
			if (scores[i] > sourceScores[i])
				score += weight[i];
		}
		return score;
	}

	public double getWeightedScore(double[] scores) {
		double weightedScore = 0;
		for (int i = 0; i < NUM_FEATURES; i++) {
			weightedScore+=scores[i] * weight[i];
		}
		return weightedScore;
	}

	public double score(int[] stats) {
		double [] scores = getScores(stats,0);
		double [] sourceScores = getScores(stats,1);
		double candScore = getWeightedScore(scores);
		double sourceScore = getWeightedScore(sourceScores);

		double penalty = 1.0;
		if (useTarget) {
			penalty = getSimplicityPenalty(candScore,target);
		}
		else penalty = getSimplicityPenalty(candScore,sourceScore);
		double BLEUscore = super.score(stats);
//		if (use_penalty == 1) {
//			if (sourceScore > candScore) {
//				penalty = Math.exp(candScore - sourceScore);
//			}
//			if (target > candScore) penalty = 0;
//		}
		if (useLinearPenalty)
			return (candScore * 8 - BLEUscore * 10) * penalty;
		return BLEUscore*penalty;
		//		return candScore * penalty;
	}

	private double getSimplicityPenalty(double this_score, double target_score) {
		if (this_score > target_score) return 1.0;
		return 0.0;
		//if this is too severe, try return Math.exp(this_score - target_score)
	}

	public void printDetailedScore_fromStats(int[] stats, boolean oneLiner) {
		double candScore = getWeightedScore(getScores(stats,0));
		double srcScore = getWeightedScore(getScores(stats,1));
		double BLEUscore = super.score(stats);
		double penalty = 1.0;
		if (useTarget) penalty = getSimplicityPenalty(candScore,target);
		else penalty = getSimplicityPenalty(candScore,srcScore);
		double[] scores = getScores(stats, 0);

		if (!oneLiner) {
			System.out.println("FINAL_SCORE= "+score(stats));
			System.out.println("CAND_W_SCORE= "+candScore);
			System.out.println();
			System.out.println("   HEIGHT_RATIO= "+scores[0]);
			System.out.println("   PHRASE/TOKEN_RATIO= " + scores[1]);
			System.out.println("   VP/NP_RATIO= "+scores[2]);
			System.out.println("   PP/NP_RATIO= "+scores[9]);
			System.out.println("   AP/NP_RATIO= "+scores[8]);
			System.out.println("   RIGHT_BRANCH= "+(4.0/scores[3]));
			System.out.println("   GRADE_LEVEL= "+scores[7]);
			System.out.println("   MEDIAN_FREQUENCY= "+stats[MEDIAN]);
			System.out.println("   AVG_SYLLABLE/TOKEN= "+ (1.0/stats[6]));
			System.out.println("   %_BASIC_WORDS= "+stats[4]);
			System.out.println("SOURCE_W_SCORE = "+srcScore);
			System.out.println("BLEU_= "+BLEUscore);
			System.out.println("penalty_= "+penalty);
		}
		else {
			// return
			// "SSB\tCAND_SS\tSRC_SS\tBLEU\tPenalty\tHEIGHT_RATIO\tPhrase/token\tVP/NP\tPP/NP\tAP/NP\tright_branch\tGL\tmed_freq\tsyll/tok\t%basic";

			System.out.print(score(stats));
			System.out.print("\t" + candScore);
			System.out.print("\t" + srcScore);
			System.out.print("\t" + BLEUscore);
			System.out.print("\t" + penalty);
			System.out.print("\t" + scores[0]);
			System.out.print("\t" + scores[1]);
			System.out.print("\t" + scores[2]);
			System.out.print("\t" + scores[9]);
			System.out.print("\t" + scores[8]);
			System.out.print("\t" + (4.0 / scores[3]));
			System.out.print("\t" + scores[7]);
			System.out.print("\t" + stats[MEDIAN]);
			System.out.print("\t" + (1.0 / stats[6]));
			System.out.println("\t" + stats[4]);
		}
	}

	public int countTotalSyllables(String[] ss) {
		int count = 0;
		for (String s : ss) count+=countSyllables(s);
		return count;
	}

	public int countSyllables(String s) {
		if (s.equals("-"))
			return 1;
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

	public static double gradeLevel(int numWords, int numSyllables) {
		return 0.39 * numWords + 11.8 * numSyllables/numWords - 15.19;
	}

	public static String getOutputHeader() {
		return "SENT#\tSSB\tCAND_SS\tSRC_SS\tBLEU\tPenalty\tHEIGHT_RATIO\tPhrase/token\tVP/NP\tPP/NP\tAP/NP\tright_branch\tGL\tmed_freq\tsyll/tok\t%basic";
	}

}