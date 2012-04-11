package joshua.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreePrint;

import joshua.zmert.EvaluationMetric;
import joshua.zmert.GradeLevelBLEU;
import joshua.zmert.SyntacticSimplicityMetric;

public class GenerateSVMFeatures {
	GenerateSVMFeatures gsf;
	static int numSentences;
	private static TreePrint constituentTreePrinter;
	private static LexicalizedParser parser;

	public static void main(String[] args) {
		String usage = "java joshua.util.GenerateSVMFEatures source_path reference_path nbestlistpath";
		if (args.length != 3) {
			System.err.println(usage);
			System.exit(1);
		}

		GenerateSVMFeatures gsf = new GenerateSVMFeatures();
		GradeLevelBLEU metric = new GradeLevelBLEU(("9 1.00 " + args[0]).split(" "));
		ArrayList<String> candidates = new ArrayList<String>();
		ArrayList<Integer> candidateIndices = new ArrayList<Integer>();
		int count = 0;
		try {
			InputStream is = new FileInputStream(new File(args[2]));
			BufferedReader br = new BufferedReader(new InputStreamReader(is, "utf8"));
			String line;
			while ((line = br.readLine()) != null) {
				if (line.contains("|||")) {
					String[] vals = line.split("\\s+\\|\\|\\|\\s+");
					candidates.add(vals[1]);
					candidateIndices.add(Integer.parseInt(vals[0]));
				} else {
					candidates.add(line);
					candidateIndices.add(count++);
				}
			}
		} catch (Exception e) {
			System.err.println("Error loading candidates from " + args[2]);
			System.exit(1);
		}
		count = candidateIndices.get(candidateIndices.size() - 1) + 1;
		String[][] refSentences = null;
		try {
			refSentences = loadReferences(args[1]);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		metric.set_numSentences(count);
		metric.set_refsPerSen(1);
		metric.set_refSentences(refSentences);
		try {
			metric.loadSources(args[0]);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		metric.initialize();
		metric.setSrcMaxNgramCounts();
		try {
			gsf.initializeParser();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		for (int i = 0; i < candidates.size(); i++) {
			int[] stats = metric.suffStats(candidates.get(i), candidateIndices.get(i));
			Tree t = gsf.parse(candidates.get(i));
			String output = "ParseLogLik:" + t.score();
			for (int j = 0; j < stats.length; j++) {
				output += " SS" + j + ":" + stats[j];
			}

			StringWriter treeStrWriter = new StringWriter();
			gsf.constituentTreePrinter.printTree(t, new PrintWriter(treeStrWriter, true));
			String parseString = treeStrWriter.toString().trim();
			System.out.println(output + "\t" + parseString + "\t" + candidates.get(i));
		}
	}

	private static String[][] loadReferences(String filename) throws IOException {
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
		String[][] refSentences = new String[numSentences][];
		for (int i = 0; i < numSentences; i++) {
			refSentences[i] = new String[1];
			refSentences[i][0] = refs.get(i);
		}
		return refSentences;
	}

	
	private void initializeParser() throws Exception {
		Runtime r = Runtime.getRuntime();
		// parser = new
		constituentTreePrinter = new TreePrint("oneline");
		parser = new LexicalizedParser(this.getClass().getResource("/joshua/zmert/resources/englishPCFG.ser.gz").getPath());
		// loadExistingParses();
	}

	public static Tree parse(String s) {
		s = s.replaceAll("\\s+", " ");
		s = s.trim();
		Tree t = parser.apply(s);
		StringWriter treeStrWriter = new StringWriter();
		constituentTreePrinter.printTree(t, new PrintWriter(treeStrWriter, true));
		String parseString = treeStrWriter.toString().trim();
		// try {
		// parseWriter.write(s + "\t" + parseString + "\t" + t.score() + "\n");
		// } catch (IOException e) {
		// System.err.println("Error writing parse to " + pathToParses);
		// e.printStackTrace();
		// }
		return t;
	}
}
