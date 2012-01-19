package joshua.zmert;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.tartarus.snowball.SnowballStemmer;
import org.tartarus.snowball.ext.englishStemmer;

import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreePrint;

public class SyntacticReadabilityMetric extends GradeLevelMetric{
	private Tree [] srcParses;
	private final HashSet<String> PHRASE_LABELS = new HashSet<String>(Arrays.asList("ADJP,ADVP,CONJP,INTJ,NP,PP,PRN,QP,RRC,UCP,VP,WHADJP,WHAVP,WHNP,WHPP".split(",")));
	private final HashSet<String> CLAUSE_LABELS = new HashSet<String>(Arrays.asList("S,SBAR,SBARQ,SINV,SQ".split(",")));
	private static HashMap<String,String> stems;
	private static HashSet<String> BASIC_WORDS;
	String basicFilePath = "research/simplification/data/be850";
	String wordFrequencyFilePath = "research/simplification/data/word_frequencies";
	String grammarPath = "research/simplification/data/englishPCFG.ser.gz";
	private static SnowballStemmer stemmer;
	private static HashMap<String,Integer> wordFrequencies;
	List<Integer> frequencies;
	private final int LOWEST_FREQUENCY = 75000;
	HashMap<String,Integer> ntCounts;
	final int SYNTACTIC_FEATURE_COUNT = 12;
	final int GL_FEATURE_COUNT = 6;
	LexicalizedParser parser;
	String pathToParses = "parsed_nbest_list";
	TreePrint constituentTreePrinter;
	Runtime r;
	FileWriter parseWriter;

	HashMap<String,String> parseMap;

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


	final int HEIGHT_RATIO = -1, VP_NP_RATIO = -2, NORM_GRADE_LEVEL = -3, NORM_MEDIAN = -4, NORM_RIGHT_BRANCH = -5;
	final int HEIGHT = 6,TOTAL_HEIGHT = 7,NUM_PHRASES = 8, NUM_NPS = 9, NUM_VPS = 10, NUM_PPS = 11, NUM_APS = 12, NUM_BASIC_WORDS = 13, NUM_WORDS = 14, NUM_CLAUSES = 15, MEDIAN = 16, RIGHT_BRANCH = 17;
	//from parent: CAND_TOKEN_LEN=0,CAND_SYLL_LEN=1,REF_TOKEN_LEN=2,REF_SYLL_LEN=3,SRC_TOKEN_LEN=4,SRC_SYLL_LEN=5;
	/* data from parent:
       int numSentences;
       int refsPerSen;
       String[][] refSentences;
	 */

	public SyntacticReadabilityMetric() {
		super();
	}

	public SyntacticReadabilityMetric(String[] options) {
		super(options);
		initialize();
	}

	@Override
	public void initialize() {
		stems = new HashMap<String,String>();
		stemmer = new englishStemmer();
		basicFilePath = "research/simplification/data/be850";
		wordFrequencyFilePath = "research/simplification/data/word_frequencies";
		grammarPath = "research/simplification/data/englishPCFG.ser.gz";
		String homeDir = System.getenv().get("HOME");
		basicFilePath = homeDir +"/"+basicFilePath;
		wordFrequencyFilePath = homeDir + "/" + wordFrequencyFilePath;
		grammarPath = homeDir + "/" + grammarPath;

		try {
			loadBasicWords();
			loadWordFrequencies();
			initializeParser();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}

		srcParses = new Tree[numSentences];
		for (int i = 0; i < numSentences; i++) {
			srcSentences[i] = removeErroneousSpace(srcSentences[i]);
			srcParses[i] = parse(srcSentences[i]);
		}

		metricName = "SYN_READ";
		toBeMinimized = false;
		suffStatsCount = SYNTACTIC_FEATURE_COUNT + GL_FEATURE_COUNT;
	}

	private void initializeParser() throws Exception {
		r = Runtime.getRuntime();
		constituentTreePrinter = new TreePrint("oneline");
		parser = new LexicalizedParser(grammarPath);
		loadExistingParses();
	}

	private void loadExistingParses() throws Exception {
		pathToParses = System.getProperty("user.dir")+"/"+pathToParses;
		File f = new File(pathToParses);
		parseMap = new HashMap<String,String>();
		if (!f.createNewFile()) {
			BufferedReader br = new BufferedReader(new FileReader(pathToParses));
			String line;
			String[] fields;
			while ( (line=br.readLine()) != null ) {
				fields = line.split("\\|\\|\\|");
				fields[0] = removeErroneousSpace(fields[0]);
				parseMap.put(fields[0],fields[1]);
			}
		}
		System.err.println("Loaded "+parseMap.size()+" existing parses from "+pathToParses);
		parseWriter = new FileWriter(pathToParses,true);
	}

	public String removeErroneousSpace(String s) {
		return s.trim().replaceAll("\\s+"," ");
	}

	public Tree parse(String s) {
		if (parseMap.containsKey(s)) {
			return Tree.valueOf(parseMap.get(s));
		}
		s = s.replaceAll("\\s+", " ");
		s = s.trim();
		Tree t = parser.apply(s);
		StringWriter treeStrWriter = new StringWriter();
		constituentTreePrinter.printTree(t, new PrintWriter(treeStrWriter, true));
		String parseString = treeStrWriter.toString().trim();
		try {
			parseWriter.write(s+" ||| "+parseString+"\n");
		} catch (IOException e) {
			System.err.println("Error writing parse to "+pathToParses);
		}
		parseMap.put(s,parseString);
		return t;
	}

	private void loadBasicWords() throws IOException {
		BASIC_WORDS = new HashSet<String>();
		BufferedReader br = new BufferedReader(new FileReader(basicFilePath));
		String line;
		while ( (line = br.readLine()) != null) {
			BASIC_WORDS.add(getStem(line));
		}
		br.close();
	}

	private void loadWordFrequencies() throws IOException {
		wordFrequencies = new HashMap<String,Integer>();
		BufferedReader br = new BufferedReader(new FileReader(wordFrequencyFilePath));
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

	@Override
	public double bestPossibleScore() {
		double d = 0;
		for (int i = 0; i < weight.length; i++)
			d+= weight[i];
		return d;
	}

	@Override
	public double worstPossibleScore() {
		return 0;
	}

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

	@Override
	public int[] suffStats(String cand_str, int i) {
		cand_str = removeErroneousSpace(cand_str);
		Tree tree = parse(cand_str);
		try {
			if (i == numSentences -2) {
				parseWriter.close();
			}
		} catch(Exception e) {
			System.err.println("Error closing the file writer for "+ pathToParses);
		}
		int[] stats = new int[suffStatsCount];

		int[] gl_stats = super.suffStats(cand_str,i);

		// get all of the stats from the grade level metric
		for (int j = 0; j < gl_stats.length; j++) {
			stats[j] = gl_stats[j];
		}

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

	public double[] getScores(int[] stats) {
		double[] scores = new double[10];
		scores[0] = normalize(1.0 * stats[HEIGHT] / stats[TOTAL_HEIGHT], HEIGHT_RATIO);
		scores[1] = normalize(1.0 * stats[NUM_PHRASES] / stats[CAND_TOKEN_LEN], 0);
		scores[2] = normalize(1.0 * stats[NUM_VPS] / stats[NUM_NPS], VP_NP_RATIO);
		scores[3] = normalize(stats[RIGHT_BRANCH], NORM_RIGHT_BRANCH);
		scores[4] = 1.0 * stats[NUM_BASIC_WORDS] / stats[NUM_WORDS];
		scores[5] = normalize(stats[MEDIAN], NORM_MEDIAN);
		scores[6] = normalize(1.0 * stats[CAND_TOKEN_LEN] / stats[CAND_SYLL_LEN], 0);
		scores[7] = normalize(gradeLevel(CAND_TOKEN_LEN, CAND_SYLL_LEN), NORM_GRADE_LEVEL);
		scores[8] = normalize(1.0 * stats[NUM_APS] / stats[NUM_NPS], VP_NP_RATIO);
		scores[9] = normalize(1.0 * stats[NUM_PPS] / stats[NUM_NPS], VP_NP_RATIO);
		return scores;
	}

	public double getWeightedScore(double[] scores) {
		double weightedScore = 0;
		for (int i = 0; i < 10; i++) {
			weightedScore+=scores[i] * weight[i];
		}
		return weightedScore;
	}
	@Override
	public double score(int[] stats) {
		double [] scores = getScores(stats);
		return getWeightedScore(scores);
	}

	@Override
	public void printDetailedScore_fromStats(int[] stats, boolean oneLiner) {
		if (!oneLiner) {
			double[] scores = getScores(stats);
			System.out.println("WEIGHTED SCORE = "+getWeightedScore(scores));
			System.out.println();
			System.out.println("   HEIGHT RATIO       = "+scores[0]);
			System.out.println("   PHRASE/TOKEN RATIO = "+scores[1]);
			System.out.println("   VP/NP RATIO        = "+scores[2]);
			System.out.println("   PP/NP RATIO        = "+scores[9]);
			System.out.println("   AP/NP RATIO        = "+scores[8]);
			System.out.println("   RIGHT BRANCH       = "+(4.0/scores[3]));
			System.out.println("   GRADE LEVEL        = "+scores[7]);
			System.out.println("   MEDIAN FREQUENCY   = "+stats[MEDIAN]);
			System.out.println("   AVG SYLLABLE/TOKEN = "+ (1.0/stats[6]));
			System.out.println("   % BASIC WORDS      = "+stats[4]);
		}
		else {
			System.out.println("WEIGHTED SCORE = "+score(stats));
		}
	}
}