package bridlensis.env;

import java.util.ArrayList;
import java.util.List;

import bridlensis.NSISStatements;

class HeaderFunction extends BuiltinFunction {

	public HeaderFunction(String name) {
		super(name);
	}

	@Override
	public String statementFor(String indent, List<TypeObject> args,
			Variable returnVar) {
		StringBuilder sb = new StringBuilder(indent);
		ArrayList<TypeObject> cArgs = new ArrayList<>(args);
		if (getReturnType() != ReturnType.VOID) {
			cArgs.add(getReturnArgIndex(), returnVar);
		}
		sb.append("${");
		sb.append(getName());
		sb.append("} ");
		for (TypeObject cArg : cArgs) {
			if (!cArg.equals(NSISStatements.NULL)) {
				sb.append(cArg.getValue());
				sb.append(' ');
			}
		}
		return sb.toString();
	}

}
