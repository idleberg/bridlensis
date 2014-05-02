package bridlensis;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Scanner;

import bridlensis.InputReader.Word;
import bridlensis.InputReader.WordTail;
import bridlensis.env.Callable;
import bridlensis.env.Callable.ReturnType;
import bridlensis.env.ComparisonStatement;
import bridlensis.env.Environment;
import bridlensis.env.EnvironmentException;
import bridlensis.env.SimpleTypeObject;
import bridlensis.env.TypeObject;
import bridlensis.env.TypeObject.Type;
import bridlensis.env.UserFunction;
import bridlensis.env.Variable;

public class Parser {

	public static final char UTF16BE_BOM = '\uFFFE';
	public static final char UTF16LE_BOM = '\uFEFF';
	public static final String NEWLINE_MARKER = "\r\n";

	private File baseDir;
	private File outDir;
	private String encoding;
	private Collection<String> excludeFiles;
	private Environment environment;
	private StatementFactory statementFactory;
	private UserFunction enclosingFunction = null;
	private int fileCount = 0;
	private int inputLines = 0;

	public Parser(Environment environment, File baseDir, File outDir,
			String encoding, Collection<String> excludeFiles) {
		this.baseDir = baseDir;
		this.outDir = outDir;
		this.encoding = encoding;
		this.environment = environment;
		this.statementFactory = new StatementFactory(environment);
		this.excludeFiles = new ArrayList<String>();
		if (excludeFiles != null) {
			this.excludeFiles.addAll(excludeFiles);
		}
	}

	public int getInputLines() {
		return inputLines;
	}

	public int getFileCount() {
		return fileCount;
	}

	public void parse(String inputFileName, String outputFileName)
			throws IOException, ParserException {
		File inputFile = new File(baseDir, inputFileName);
		System.out.println("Begin parse file: " + inputFile.getAbsolutePath());
		BufferedWriter writer = null;
		try {
			writer = getOutputWriter(outputFileName);
			writer.write(statementFactory.nullDefine());
			parseFile(inputFile, writer);
		} finally {
			if (writer != null) {
				try {
					writer.flush();
					writer.close();
				} catch (IOException e) {
					// Ignore
				}
			}
		}
	}

	private BufferedWriter getOutputWriter(String outputFileName)
			throws UnsupportedEncodingException, FileNotFoundException,
			IOException {
		File outputFile = new File(outDir, outputFileName);
		System.out.println("Output file: " + outputFile.getAbsolutePath());
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
				new FileOutputStream(outputFile), encoding));
		if (encoding.equalsIgnoreCase("UTF-16LE")) {
			writer.write(UTF16LE_BOM);
		} else if (encoding.equalsIgnoreCase("UTF-16BE")) {
			writer.write(UTF16BE_BOM);
		}
		return writer;
	}

	private void parseFile(File inputFile, BufferedWriter writer)
			throws IOException, ParserException {
		Scanner source = new Scanner(inputFile, encoding);
		InputReader reader = new InputReader(source);
		fileCount++;
		try {
			while (reader.goToNextStatement()) {
				writer.write(parseStatement(reader));
				writer.write(Parser.NEWLINE_MARKER);
			}
			System.out.println(String.format(
					"End parsing %d lines in file %s.", reader.getLinesRead(),
					inputFile.getAbsolutePath()));
			inputLines += reader.getLinesRead();
		} catch (InvalidSyntaxException | EnvironmentException e) {
			throw new ParserException(inputFile.getAbsolutePath(),
					reader.getLinesRead(), e);
		} finally {
			source.close();
		}
	}

	protected String parseStatement(InputReader reader)
			throws InvalidSyntaxException, EnvironmentException,
			ParserException {
		if (!reader.hasNextWord()) {
			return reader.getCurrentStatement();
		}
		Word word = reader.nextWord();
		String keyword = word.asName();
		if (reader.getWordTail().isAssignment()) {
			return parseVarAssign(word, reader);
		} else if (reader.getWordTail().isFunctionArgsOpen()) {
			return parseCall(word, null, reader);
		} else if (keyword.equals("var")) {
			return parseVarDeclare(reader);
		} else if (keyword.equals("function")) {
			return parseFunctionBegin(reader);
		} else if (keyword.equals("return")) {
			return parseFunctionReturn(reader);
		} else if (keyword.equals("functionend")) {
			return parseFunctionEnd(reader);
		} else if (keyword.equals("if") || keyword.equals("elseif")) {
			return parseIf(word, reader);
		} else if (keyword.equals("else")) {
			return statementFactory.logicLibDefine(reader.getIndent(), "Else");
		} else if (keyword.equals("endif")) {
			return statementFactory.logicLibDefine(reader.getIndent(), "EndIf");
		} else if (keyword.equals("do")) {
			return parseDoLoop("Do", reader);
		} else if (keyword.equals("continue")) {
			return statementFactory.logicLibDefine(reader.getIndent(),
					"Continue");
		} else if (keyword.equals("break")) {
			return statementFactory.logicLibDefine(reader.getIndent(), "Break");
		} else if (keyword.equals("loop")) {
			return parseDoLoop("Loop", reader);
		} else if (reader.getWordTail().isCompilerCommand()
				&& reader.nextWord().asName().equals("include")) {
			return parseInclude(reader);
		}
		return reader.getCurrentStatement();
	}

	private String parseDoLoop(String keyword, InputReader reader)
			throws InvalidSyntaxException, EnvironmentException {
		String define = Character.toUpperCase(keyword.charAt(0))
				+ keyword.substring(1);
		if (!reader.hasNextWord()) {
			return statementFactory.logicLibDefine(reader.getIndent(), define);
		}
		StringBuilder sb = new StringBuilder();
		ComparisonStatement statement = parseComparisonStatement(
				reader.nextWord(), reader, sb);
		if (statement.isNot()) {
			throw new InvalidSyntaxException(String.format(
					"Illegal modifier 'Not' in %s statement", define));
		}
		sb.append(statementFactory.logicLibComparisonStatement(
				reader.getIndent(), define, statement));
		return sb.toString();
	}

	private String parseIf(Word keyword, InputReader reader)
			throws InvalidSyntaxException, EnvironmentException {
		StringBuilder sb = new StringBuilder();
		StringBuilder buffer = new StringBuilder();
		ComparisonStatement ifStatement = parseComparisonStatement(keyword,
				reader, buffer);
		sb.append(statementFactory.logicLibComparisonStatement(
				reader.getIndent(), ifStatement));
		while (reader.hasNextWord()) {
			sb.append(Parser.NEWLINE_MARKER);
			sb.append(statementFactory.logicLibComparisonStatement(
					reader.getIndent(),
					parseComparisonStatement(reader.nextWord(), reader, buffer)));
		}
		if (buffer.length() > 0) {
			sb.insert(0, buffer.toString());
		}
		return sb.toString();
	}

	private ComparisonStatement parseComparisonStatement(Word keyword,
			InputReader reader, StringBuilder buffer)
			throws InvalidSyntaxException, EnvironmentException {
		String key = keyword.getValue();
		if (key.equalsIgnoreCase("and") || key.equalsIgnoreCase("or")) {
			key += "If";
		}
		ComparisonStatement statement = new ComparisonStatement(key);

		Word left = reader.nextWord();
		if (left.asName().equals("not")) {
			statement.setNot(true);
			left = reader.nextWord();
		}
		statement.addLeft(parseExpression(left, buffer, reader));

		while (reader.hasNextWord()) {
			String compare = reader.getWordTail().getComparison();
			if (compare.isEmpty()) {
				statement.addLeft(parseExpression(reader.nextWord(), buffer,
						reader));
			} else {
				statement.setCompare(compare);
				break;
			}
		}

		if (reader.hasNextWord()) {
			statement.addRight(parseExpression(reader.nextWord(), buffer,
					reader));
		}

		return statement;
	}

	private String parseInclude(InputReader reader)
			throws InvalidSyntaxException, ParserException {
		String inputFileName = reader.nextWord().asBareString();
		File inputFile = new File(baseDir, inputFileName);
		String statement;
		if (excludeFiles.contains(inputFileName)
				|| excludeFiles.contains(inputFile.getAbsolutePath())) {
			System.out.println(String.format(
					"Include file '%s' omitted being marked as excluded.",
					inputFileName));
			String outputFileName = MakeBridleNSIS
					.getBridleNSISFileName(inputFileName);
			File outputFile = new File(outDir, outputFileName);
			// Copy include file to outdir
			System.out.println(String.format(
					"Copy file '%s' to directory '%s'",
					inputFile.getAbsolutePath(), outDir.getAbsolutePath()));
			copyFile(inputFile, outputFile, reader.getLinesRead());
			statement = statementFactory.include(reader.getIndent(),
					outputFileName);
		} else if (!inputFile.exists()) {
			System.out
					.println(String
							.format("Include file '%s' not found, assuming it's found by NSIS.",
									inputFileName));
			statement = reader.getCurrentStatement();
		} else {
			System.out
					.println("Follow include: " + inputFile.getAbsolutePath());
			String outputFileName = MakeBridleNSIS
					.getBridleNSISFileName(inputFileName);
			try {
				BufferedWriter writer = null;
				try {
					writer = getOutputWriter(outputFileName);
					parseFile(inputFile, writer);
				} finally {
					if (writer != null) {
						try {
							writer.flush();
							writer.close();
						} catch (IOException e) {
							// Ignore
						}
					}
				}
			} catch (ParserException | IOException e) {
				throw new InvalidSyntaxException(e.getMessage(), e);
			}
			statement = statementFactory.include(reader.getIndent(),
					outputFileName);
		}
		return statement;
	}

	private void copyFile(File sourceFile, File destFile, int lineNumber)
			throws ParserException {
		FileChannel source = null;
		FileChannel destination = null;
		try {
			if (!destFile.exists()) {
				destFile.createNewFile();
			}
			source = new FileInputStream(sourceFile).getChannel();
			destination = new FileOutputStream(destFile).getChannel();
			destination.transferFrom(source, 0, source.size());
		} catch (IOException e) {
			throw new ParserException(sourceFile.getAbsolutePath(), lineNumber,
					e);
		} finally {
			if (source != null) {
				try {
					source.close();
				} catch (IOException e) {
					// Ignore
				}
			}
			if (destination != null) {
				try {
					destination.close();
				} catch (IOException e) {
					// Ignore
				}
			}
		}
	}

	private String parseVarDeclare(InputReader reader)
			throws InvalidSyntaxException, EnvironmentException {
		Word name = reader.nextWord();
		Variable variable = environment.registerVariable(name.asName(),
				enclosingFunction);
		return statementFactory.variableDeclare(reader.getIndent(), variable);
	}

	private String parseVarAssign(Word varName, InputReader reader)
			throws InvalidSyntaxException, EnvironmentException {
		StringBuilder sb = new StringBuilder();
		Variable variable;

		if (environment.containsVariable(varName.asName(), enclosingFunction)) {
			variable = environment.getVariable(varName.asName(),
					enclosingFunction);
		} else {
			variable = registerAndDeclareVariable(varName.asName(),
					reader.getIndent(), sb);
		}

		Word word = reader.nextWord();
		TypeObject value;
		WordTail tail = reader.getWordTail();
		if (tail.isConcatenation()) {
			value = parseExpression(word, sb, reader);
		} else if (tail.isFunctionArgsOpen()) {
			// Direct function return assign to avoid declaring yet another
			// dummy variable for function return
			sb.append(parseCall(word, variable, reader));
			if (reader.getWordTail().isConcatenation()) {
				sb.append(Parser.NEWLINE_MARKER);
				value = parseExpression(variable, sb, reader);
			} else {
				return sb.toString();
			}
		} else if (word.getType() == Type.NAME) {
			value = environment.getVariable(word.asName(), enclosingFunction);
		} else {
			value = word;
		}
		sb.append(statementFactory.variableAssign(reader.getIndent(), variable,
				value));
		return sb.toString();
	}

	private String parseFunctionBegin(InputReader reader)
			throws InvalidSyntaxException, EnvironmentException {
		if (enclosingFunction != null) {
			throw new InvalidSyntaxException(
					"Cannot declare function within a function");
		}
		enclosingFunction = environment.registerUserFunction(reader.nextWord()
				.asName());
		StringBuilder sb = new StringBuilder();
		while (reader.hasNextWord()) {
			String argName = reader.nextWord().asName();
			Variable argVariable = registerAndDeclareVariable(argName,
					reader.getIndent(), sb);
			enclosingFunction.addArgument(argVariable);
		}
		sb.append(statementFactory.functionBegin(reader.getIndent(),
				enclosingFunction));
		return sb.toString();
	}

	private String parseFunctionReturn(InputReader reader)
			throws InvalidSyntaxException, EnvironmentException {
		if (!(enclosingFunction != null)) {
			throw new InvalidSyntaxException(
					"Return is not allowed outside function");
		}
		StringBuilder sb = new StringBuilder();
		TypeObject value = null;
		if (reader.hasNextWord()) {
			enclosingFunction.setHasReturn(true);
			Word word = reader.nextWord();
			WordTail tail = reader.getWordTail();
			if (tail.isFunctionArgsOpen() || tail.isConcatenation()) {
				value = parseExpression(word, sb, reader);
			} else if (word.getType() == Type.NAME) {
				value = environment.getVariable(word.asName(),
						enclosingFunction);
			} else {
				value = word;
			}
		}
		sb.append(statementFactory.functionReturn(reader.getIndent(),
				enclosingFunction, value));
		return sb.toString();
	}

	private String parseFunctionEnd(InputReader reader)
			throws InvalidSyntaxException {
		if (!(enclosingFunction != null)) {
			throw new InvalidSyntaxException(
					"FunctionEnd is not allowed outside function");
		}
		enclosingFunction = null;
		return statementFactory.functionEnd(reader.getIndent());
	}

	private String parseCall(Word name, Variable returnVar, InputReader reader)
			throws InvalidSyntaxException, EnvironmentException {
		StringBuilder sb = new StringBuilder();
		Callable callable = environment.getCallable(name.asName());
		List<TypeObject> args = parseAndValidateFunctionArguments(callable,
				returnVar, reader, sb);
		sb.append(statementFactory.call(reader.getIndent(), callable, args,
				returnVar));
		return sb.toString();
	}

	private List<TypeObject> parseAndValidateFunctionArguments(
			Callable function, Variable returnVar, InputReader reader,
			StringBuilder buffer) throws InvalidSyntaxException,
			EnvironmentException {
		if (function.getReturnType() == ReturnType.VOID && returnVar != null) {
			throw new InvalidSyntaxException("Function doesn't return a value");
		}
		ArrayList<TypeObject> args = new ArrayList<>();
		WordTail tail = reader.getWordTail();
		while (reader.hasNextWord() && !tail.containsFunctionArgsClose()) {
			TypeObject arg;
			Word word = reader.nextWord();
			tail = reader.getWordTail();
			if (!tail.isFunctionArgsClose()
					&& (tail.isFunctionArgsOpen() || tail.isConcatenation())) {
				arg = parseExpression(word, buffer, reader);
			} else if (word.getType() == Type.NAME) {
				Variable variable = environment.getVariable(word.asName(),
						enclosingFunction);
				arg = variable;
			} else {
				arg = word;
			}
			args.add(arg);
		}
		if (function.getArgsCount() > -1
				&& args.size() > function.getArgsCount()) {
			throw new InvalidSyntaxException(
					String.format(
							"Too many function arguments (expected at most %d, provided %d)",
							function.getArgsCount(), args.size()));
		} else {
			if (args.size() < function.getMandatoryArgsCount()) {
				throw new InvalidSyntaxException(
						String.format(
								"Too few function arguments provided (expected at minimum %d, provided %d)",
								function.getMandatoryArgsCount(), args.size()));
			}
		}
		// Push empty arguments for function if they're not defined
		for (int i = args.size(); i < function.getArgsCount(); i++) {
			args.add(StatementFactory.NULL);
		}
		return args;
	}

	protected TypeObject parseExpression(TypeObject expr, StringBuilder buffer,
			InputReader reader) throws InvalidSyntaxException,
			EnvironmentException {
		TypeObject object;
		WordTail tail = reader.getWordTail();
		if (tail.isFunctionArgsOpen()) {
			Variable fReturn = parseInExpressionCall(expr, buffer, reader);
			if (tail.isComparison()) {
				object = fReturn;
			} else {
				object = parseExpression(fReturn, buffer, reader);
			}
		} else if (tail.isConcatenation()) {
			TypeObject concat = concatenateWithNext(expr, buffer, reader);
			object = parseExpression(concat, buffer, reader);
		} else if (expr.getType() == Type.NAME) {
			object = environment
					.getVariable(expr.getValue(), enclosingFunction);
		} else {
			object = expr;
		}
		return object;
	}

	private TypeObject concatenateWithNext(TypeObject left,
			StringBuilder buffer, InputReader reader)
			throws EnvironmentException, InvalidSyntaxException {
		String leftValue;
		if (left.getType() == Type.NAME) {
			leftValue = environment.getVariable(left.getValue().toLowerCase(),
					enclosingFunction).getValue();
		} else {
			leftValue = StatementFactory.deString(left);
		}

		String rightValue = StatementFactory.deString(parseExpression(
				reader.nextWord(), buffer, reader));

		return new SimpleTypeObject(Type.STRING, String.format("%s%s",
				leftValue, rightValue));
	}

	private Variable parseInExpressionCall(TypeObject callableName,
			StringBuilder buffer, InputReader reader)
			throws InvalidSyntaxException, EnvironmentException {
		Variable fReturn = registerAndDeclareVariable(environment
				.getNameGenerator().generate(), reader.getIndent(), buffer);
		buffer.append(parseCall(new Word(callableName.getValue()), fReturn,
				reader));
		buffer.append(Parser.NEWLINE_MARKER);
		return fReturn;
	}

	private Variable registerAndDeclareVariable(String name, String indent,
			StringBuilder buffer) throws EnvironmentException {
		String varName = (name == null) ? environment.getNameGenerator()
				.generate() : name;
		Variable variable = environment.registerVariable(varName,
				enclosingFunction);
		buffer.append(statementFactory.variableDeclare(indent, variable));
		buffer.append(Parser.NEWLINE_MARKER);
		return variable;
	}

}
