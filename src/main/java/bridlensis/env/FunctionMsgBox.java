package bridlensis.env;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import bridlensis.InvalidSyntaxException;
import bridlensis.NSISStatements;
import bridlensis.env.TypeObject.Type;

class FunctionMsgBox extends CustomFunction {

	private static final int BUTTONGROUP_INDEX = 0;
	private static final int MESSAGE_INDEX = 1;
	private static final int OPTIONS_INDEX = 2;
	private static final int SDRETURN_INDEX = 3;

	private static final String GOTO_PREFIX = "msgbox_";

	private static List<String> buttons = Arrays.asList("OK", "CANCEL",
			"ABORT", "RETRY", "IGNORE", "YES", "NO");

	protected static enum ButtonGroup {
		OK, OKCANCEL, ABORTRETRYIGNORE, RETRYCANCEL, YESNO, YESNOCANCEL
	};

	private static final List<TypeObject> RETURN_ARG_YES = new ArrayList<TypeObject>(
			Arrays.asList(new SimpleTypeObject(Type.STRING, "YES")));
	private static final List<TypeObject> RETURN_ARG_RETRY = new ArrayList<TypeObject>(
			Arrays.asList(new SimpleTypeObject(Type.STRING, "RETRY")));
	private static final List<TypeObject> RETURN_ARG_OK = new ArrayList<TypeObject>(
			Arrays.asList(new SimpleTypeObject(Type.STRING, "OK")));

	protected static class ReturnOption implements TypeObject {

		private final String returnValue;
		private final String goTo;

		public ReturnOption(String returnValue, String goToId) {
			this.returnValue = returnValue;
			this.goTo = GOTO_PREFIX + goToId;
		}

		public String getGoTo() {
			return goTo;
		}

		@Override
		public Type getType() {
			return Type.STRING;
		}

		@Override
		public String getValue() {
			return '"' + returnValue + '"';
		}

		public String getID() {
			return "ID" + returnValue;
		}

	}

	private NameGenerator nameGenerator;
	private Callable strcpy;

	protected FunctionMsgBox(NameGenerator nameGenerator, Callable strcpy) {
		super(2, ReturnType.OPTIONAL, "MsgBox");
		this.nameGenerator = nameGenerator;
		this.strcpy = strcpy;
		registerArguments("button_group", "message", "options", "sd_return");
	}

	protected Collection<ReturnOption> returnOptions(ButtonGroup buttons)
			throws InvalidSyntaxException {
		switch (buttons) {
		case OK:
			return Arrays.asList();
		case OKCANCEL:
			return Arrays.asList(new ReturnOption("CANCEL", nameGenerator
					.generate()));
		case YESNO:
			return Arrays.asList(new ReturnOption("NO", nameGenerator
					.generate()));
		case YESNOCANCEL:
			return Arrays.asList(
					new ReturnOption("NO", nameGenerator.generate()),
					new ReturnOption("CANCEL", nameGenerator.generate()));
		case RETRYCANCEL:
			return Arrays.asList(new ReturnOption("CANCEL", nameGenerator
					.generate()));
		case ABORTRETRYIGNORE:
			return Arrays.asList(
					new ReturnOption("ABORT", nameGenerator.generate()),
					new ReturnOption("IGNORE", nameGenerator.generate()));
		}
		throw new InvalidSyntaxException(String.format(
				"Unsupported buttons definition '%s'", buttons));
	}

	public static String optionsList(ButtonGroup buttons, String options) {
		StringBuilder sb = new StringBuilder();
		sb.append("MB_");
		sb.append(buttons.toString());
		if (options != null) {
			for (String o : options.split("\\|")) {
				if (o.length() > 0) {
					sb.append("|MB_");
					sb.append(o);
				}
			}
		}
		return sb.toString();
	}

	@Override
	public String statementFor(String indent, List<TypeObject> args,
			Variable returnVar) throws InvalidSyntaxException {
		ButtonGroup buttonGroup;
		try {
			buttonGroup = ButtonGroup.valueOf(SimpleTypeObject.stripString(args
					.get(BUTTONGROUP_INDEX)));
		} catch (IllegalArgumentException e) {
			throw new InvalidSyntaxException(String.format(
					"Invalid button group argument '%s'",
					args.get(BUTTONGROUP_INDEX)));
		}
		StringBuilder sb = new StringBuilder(indent);
		sb.append("MessageBox ");
		sb.append(optionsList(buttonGroup,
				args.get(OPTIONS_INDEX).equals(NSISStatements.NULL) ? null
						: SimpleTypeObject.stripString(args.get(OPTIONS_INDEX))));
		sb.append(' ');
		sb.append(args.get(MESSAGE_INDEX).getValue());

		if (!args.get(SDRETURN_INDEX).equals(NSISStatements.NULL)) {
			String button = SimpleTypeObject.stripString(args
					.get(SDRETURN_INDEX));
			if (!buttons.contains(button.toUpperCase())) {
				throw new InvalidSyntaxException(
						"Unsupported MsgBox SD return " + button);
			}
			sb.append(" /SD ID");
			sb.append(button);
		}

		if (returnVar != null) {
			String exit_jump = GOTO_PREFIX + nameGenerator.generate();
			indent += NSISStatements.DEFAULT_INDENT;
			StringBuilder sbRet = new StringBuilder();
			for (ReturnOption ro : returnOptions(buttonGroup)) {
				sb.append(' ');
				sb.append(ro.getID());
				sb.append(' ');
				sb.append(ro.getGoTo());
				sbRet.append(NSISStatements.NEWLINE_MARKER);
				sbRet.append(NSISStatements.label(indent, ro.getGoTo()));
				sbRet.append(NSISStatements.NEWLINE_MARKER);
				sbRet.append(indent);
				sbRet.append(strcpy.statementFor(NSISStatements.DEFAULT_INDENT,
						Arrays.asList((TypeObject) ro), returnVar));
				sbRet.append(NSISStatements.NEWLINE_MARKER);
				sbRet.append(indent);
				sbRet.append(NSISStatements.goTo(NSISStatements.DEFAULT_INDENT,
						exit_jump));
			}

			sb.append(NSISStatements.NEWLINE_MARKER);
			switch (buttonGroup) {
			case OK:
			case OKCANCEL:
				sb.append(strcpy.statementFor(NSISStatements.DEFAULT_INDENT,
						RETURN_ARG_OK, returnVar));
				break;
			case RETRYCANCEL:
			case ABORTRETRYIGNORE:
				sb.append(strcpy.statementFor(NSISStatements.DEFAULT_INDENT,
						RETURN_ARG_RETRY, returnVar));
				break;
			case YESNO:
			case YESNOCANCEL:
				sb.append(strcpy.statementFor(NSISStatements.DEFAULT_INDENT,
						RETURN_ARG_YES, returnVar));
				break;
			}

			sb.append(NSISStatements.NEWLINE_MARKER);
			sb.append(NSISStatements.goTo(NSISStatements.DEFAULT_INDENT,
					exit_jump));
			sb.append(sbRet);
			sb.append(NSISStatements.NEWLINE_MARKER);
			sb.append(NSISStatements.label(indent, exit_jump));
		}

		return sb.toString();
	}

	@Override
	public String getMarkdownHelp() {
		return "Displays a message box with buttons `buttons` containing the text `message`. \r\n"
				+ "\r\n"
				+ "*   __buttons__: One of the following `OK`, `OKCANCEL`, `ABORTRETRYIGNORE`, "
				+ "                 `RETRYCANCEL`, `YESNO`, or `YESNOCANCEL`\r\n"
				+ "*   __message__: Message text\r\n"
				+ "*   __options__: `|` separated list of zero or more options: "
				+ "                 `ICONEXCLAMATION`, `ICONINFORMATION`, `ICONQUESTION`, "
				+ "                 `ICONSTOP`, `USERICON`, `TOPMOST`, `SETFOREGROUND`, `RIGHT`, "
				+ "                 `RTLREADING`, `DEFBUTTON1`, `DEFBUTTON2`, `DEFBUTTON3`, and "
				+ "                 `DEFBUTTON4`. Refer to the NSIS MessageBox instruction "
				+ "                 documentation for details.\r\n"
				+ "*   __sd__: Silent installer default return. Use empty string (or simply omit "
				+ "            the argument) if message box is shown in silent install.\r\n"
				+ "\r\n"
				+ "Function will return name of the button user selected: `OK`, `CANCEL`, "
				+ "`ABORT`, `RETRY`, `IGNORE`, `YES`, or `NO`.\r\n"
				+ "\r\n"
				+ "    If MsgBox(\"YESNO\", \"Are you sure?\") == \"YES\"\r\n"
				+ "        ...\r\n";
	}

}
