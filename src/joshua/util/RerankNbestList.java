package joshua.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class RerankNbestList extends JoshuaEval {

	static int numSents = 100;
	
	public static void main(String[] args) {

		try {
			loadReferences(args[0]);
		} catch (IOException e) {
			System.err.println("Error loading references");
			e.printStackTrace();
			System.exit(1);
		}
		try {
			loadNbestList(args[1]);
		} catch (IOException e) {
			System.err.println("Error loading nbestList");
			e.printStackTrace();
			System.exit(1);
		}
	
	}
	
	private static void loadNbestList(String filename) throws IOException {
		InputStream is = new FileInputStream(new File(filename));
		BufferedReader br = new BufferedReader(new InputStreamReader(is, "utf8"));
		ArrayList<String> candidateSents = new ArrayList<String>();
		int index = 0;
		String line;
		String candidate_str;
		while ( (line = br.readLine()) != null) {
			int read_i = Integer.parseInt(line.substring(0,line.indexOf(" |||")).trim());
			if (read_i == index) {
				line = line.substring(line.indexOf("||| ")+4); // get rid of initial text
				candidate_str = line.substring(0,line.indexOf(" |||"));
				candidateSents.add(normalize(candidate_str, 1));
			}
			else {
				rankCandidates(candidateSents);
				index++;
				candidateSents = new ArrayList<String>();
				line = line.substring(line.indexOf("||| ")+4); // get rid of initial text
				candidate_str = line.substring(0,line.indexOf(" |||"));
				candidateSents.add(normalize(candidate_str, 1));
			}
		}
	}


	
	
	private static void rankCandidates(ArrayList<String> candidateSents) {
		
	}




	static String[] refs;
	private static void loadReferences(String filename) throws IOException {
		refs = new String[numSents];
		InputStream is = new FileInputStream(new File(filename));
		BufferedReader br = new BufferedReader(new InputStreamReader(is, "utf8"));
		String line;
		int i = 0;
		while ( (line = br.readLine()) != null) {
			refs[i++] = normalize(line,1);
		}
		br.close();
		is.close();
	}
	

	
	
	
}
