package org.hipi.tools.face;

import java.io.BufferedReader;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.IntBuffer;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.Parser;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.SerializationUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.MapFile;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableFactories;
import org.apache.hadoop.io.WritableUtils;
import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_face;
import org.bytedeco.javacpp.opencv_core.Mat;
import org.bytedeco.javacpp.opencv_core.MatVector;
import org.bytedeco.javacpp.opencv_face.FaceRecognizer;
import org.hipi.opencv.OpenCVMatWritable;
import org.hipi.tools.face.AngelSerialized;

public class FaceRecognitionTraining {

	private static FaceRecognizer faceRecognizer = null;
	private static final Options options = new Options();
	private static final Parser parser = (Parser) new BasicParser();

	private static int recognizerMethod = 1;
	private static String saveLocation = "";
	private static String inputPath = "";
	private static String outputPath = "/root/hipi";
	private static boolean faceRecognizerLoaded = false;
	private static boolean forceTrainig = false;
	private static boolean hdfsInput = false;

	static {
		options.addOption("f", "force", false, "force overwrite if output HIB already exists");
		options.addOption("h", "hdfs-input", false, "assume input directory is on HDFS");
		options.addOption("a", "action", true,
				"faceDetection FD, FaceRecognition FR, FaceRecognitionSingle thread NonFR,  its a Must");
		options.addOption("m", "recognition-method", true,
				"LBPHFaceRecognizer = 1, FisherFaceRecognizer = 2," + " EigenFaceRecognizer = 3 ");
		options.addOption("mp", "image-limit-percentage", true,
				"Maximun number of images to be load per folder by percentage");
	}

	private static void usage() {
		// usage
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp("face.jar [options] <image directory HIB> <image(s) to predict>", options);
		System.exit(0);
	}

	public static int run(String args[], Configuration conf) {

		String peopleListDir = conf.get("hipi.people.face.recognition.path");
		try {
			// Access people hashmap data on HDFS
			if (peopleListDir == null) {
				System.err.println("People List do not exists - cannot continue. Exiting.");
				System.exit(1);
			}

			Path peopleListPath = new Path(peopleListDir);
			FSDataInputStream dis = FileSystem.get(conf).open(peopleListPath);
			System.out.println(peopleListPath + " available: " + dis.available());
			MapWritable hashMapPeople = new MapWritable();
			hashMapPeople.clear();
			hashMapPeople.readFields(dis);

			dis.close();

			int count = 0;
			for (Entry<Writable, Writable> entrySet : hashMapPeople.entrySet()) {
				if (entrySet.getValue() != null) {
					ArrayWritable imagesArray = ((ArrayWritable) entrySet.getValue());
					if (imagesArray.get() != null)
						count += imagesArray.get().length;
				}
			}
			System.out.println(" Total Images: " + count);

			MatVector imagesTemp = new MatVector(count);
			Mat labelsTemp = new Mat(count, 1, opencv_core.CV_32SC1);
			IntBuffer labelsBuf = labelsTemp.createBuffer();

			int counter = 0;
			for (Entry<Writable, Writable> entrySet : hashMapPeople.entrySet()) {
				Text key = (Text) entrySet.getKey();
				String keyS = key.toString();
				int label = Integer.parseInt(keyS.substring(keyS.lastIndexOf("_") + 1));
				System.out.println("Name: " + keyS + " label: " + label);
				ArrayWritable imagesArray = ((ArrayWritable) entrySet.getValue());

				for (Writable img : imagesArray.get()) {
					OpenCVMatWritable matWritable = (OpenCVMatWritable) img;
					Mat matImg = matWritable.getMat();
					imagesTemp.put(counter, matImg);
					labelsBuf.put(counter, label);
					counter++;
				}
			}

			try {
				config(args);
			} catch (Exception e) {
				e.printStackTrace();
			}
			if (imagesTemp == null || labelsTemp == null) {
				return 1;
			}

			String fileName = inputPath.substring(inputPath.lastIndexOf(File.separator));
			configFRG(fileName);

			System.out.println("training");
			faceRecognizer.train(imagesTemp, labelsTemp);
			System.out.println("trained");

			System.out.println("saving training");
			faceRecognizer.save(saveLocation);

			System.out.println(saveLocation);

			// success
			return 0;

		} catch (Exception e) {
			e.printStackTrace();
			return 1;
		}

	}

	private static void config(String[] args) throws Exception {
		// Attempt to parse the command line arguments
		CommandLine line = null;
		try {
			line = parser.parse(options, args);
		} catch (ParseException exp) {
			exp.printStackTrace();
			usage();
		}
		if (line == null) {
			usage();
		}

		String[] leftArgs = line.getArgs();
		if (leftArgs.length != 2) {
			usage();
		}
		inputPath = leftArgs[0];
		// outputPath = leftArgs[1];

		if (line.hasOption("h")) {
			hdfsInput = true;
		}

		if (line.hasOption("f")) {
			forceTrainig = true;
		}

		if (line.hasOption("m")) {
			String method = line.getOptionValue("m");
			recognizerMethod = Integer.parseInt(method);
			if (recognizerMethod < 0 || recognizerMethod > 3) {
				throw new Exception("Method has not been programmed");
			}
		}

	}

	private static void configFRG(String name) {

		switch (recognizerMethod) {
		case 1:
			System.out.println("opencv_face.createLBPHFaceRecognizer()");
			faceRecognizer = opencv_face.createLBPHFaceRecognizer();
			saveLocation = outputPath + name + ".lbph.predict.opencv";
			break;
		case 2:
			System.out.println("opencv_face.createFisherFaceRecognizer()");
			faceRecognizer = opencv_face.createFisherFaceRecognizer();
			saveLocation = outputPath + name + ".fisher.predict.opencv";
			break;
		case 3:
			System.out.println("opencv_face.createEigenFaceRecognizer()");
			faceRecognizer = opencv_face.createEigenFaceRecognizer();
			saveLocation = outputPath + name + ".eigen.predict.opencv";
			break;
		default:
			System.err.println("Method do not exists");
			System.exit(0);
		}

		if (!forceTrainig && new File(saveLocation).exists()) {
			faceRecognizer.load(saveLocation);
			faceRecognizerLoaded = true;
			return;
		}
	}
}
