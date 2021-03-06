package main.generators.crud;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeMirror;

import main.annotations.Endpoint;
import main.exceptions.EntityNotFoundException;
import main.generators.args.ThreeArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import main.annotations.CRUD;
import main.annotations.Filter;
import main.generators.CodeGenerator;
import main.generators.Generator;
import main.generators.args.TwoArgs;

public class ServiceGenerator extends Generator<ThreeArgs<String, CRUD, List<? extends Element>>> {


	private String suffix;
	private String repSuffix;
	private String classesPrefix;

	private boolean pagination;

	public ServiceGenerator(CodeGenerator codeGenerator, String suffix, String repSuffix, String classesPrefix) {

		super(codeGenerator);
		
		this.suffix = suffix;
		this.repSuffix = repSuffix;
		this.classesPrefix = classesPrefix;
	}

	public TypeSpec generate(ThreeArgs<String, CRUD, List<? extends Element>> args) {

	    String name = args.one();
	    CRUD crud = args.two();
	    List<? extends Element> endpoints = args.three();
	  
		this.pagination = crud.pagination();

		MethodSpec save = this.createMethod("save")
			.addParameter(this.eleUtils.elementParam())
			.addStatement("return $T.ok(this.repository.save(entity))", ResponseEntity.class)
			.build();

		MethodSpec one = this.createMethod("one")
			.addException(EntityNotFoundException.class)
			.addParameter(this.eleUtils.elementIdParam())
			.addStatement("return $T.ok(this.repository.findById(id)\n"
						+".orElseThrow(() -> new $T($S, id)))",
				ResponseEntity.class, EntityNotFoundException.class, name)
			.build();

		MethodSpec all = crud.filter().fields().length > 0 ? this.filterableAll(crud.filter()) : this.simpleAll();

		MethodSpec delete = this.createMethod("delete")
			.addParameter(this.eleUtils.elementIdParam())
			.addStatement("this.repository.deleteById(id)")
			.addStatement("return new $T($T.NO_CONTENT)", ResponseEntity.class, HttpStatus.class)
			.build();

		String repositoryClassName = this.classesPrefix + name + this.repSuffix;
		
		TypeSpec.Builder builder = TypeSpec.classBuilder(this.classesPrefix + name + this.suffix)
			.addModifiers(Modifier.PUBLIC)
			.addAnnotation(Component.class)
			.addField(
			    FieldSpec.builder(
			        ClassName.get("", repositoryClassName), "repository")
			        .addAnnotation(Autowired.class)
			        .build())
			.addMethod(save)
			.addMethod(one)
			.addMethod(all)
			.addMethod(delete);

		endpoints.forEach(el -> builder.addMethod(createEndpoint(el)));

		return builder.build();
	}


	private MethodSpec createEndpoint(Element element) {

		String elementName = element.getSimpleName().toString();
		elementName = String.valueOf(elementName.charAt(0)).toUpperCase() + elementName.substring(1);

		return createMethod(element.getSimpleName().toString())
			.addParameter(this.eleUtils.elementIdParam())
			.addException(EntityNotFoundException.class)
			.addStatement("return $T.ok(this.repository.findById(id)\n"
				+ ".orElseThrow(() -> new $T($S, id))"
				+ ".get" + elementName + "())",
				ResponseEntity.class,
				EntityNotFoundException.class,
				element.getEnclosingElement().getSimpleName().toString())
			.build();
	}

	private MethodSpec simpleAll() {

		MethodSpec.Builder builder = this.createMethod("all");

		if (!this.pagination)
			builder.addStatement("return $T.ok(this.repository.findAll())", ResponseEntity.class);
		else {
			addPageability(builder);
			builder
				.addStatement("return $T.ok(this.repository.findAll(pageable))", ResponseEntity.class);
		}

		return builder
			.build();
	}

	private MethodSpec filterableAll(Filter filter) {

		String[] fields = filter.fields();

		if (fields.length == 1 && "*".equals(fields[0])) {

			fields = this.eleUtils
				.getNonTransientFieldsNames()
				.toArray(new String[]{});
		}

		MethodSpec.Builder builder = this.addArguments(
			this.createMethod("allByFilter"), fields);

		if (this.pagination)
			addPageability(builder);

		return builder
			.addStatement(getReturn(fields), ResponseEntity.class)
			.build();
	}

	private String getReturn(String[] fields) {

		StringBuilder builder = new StringBuilder("return $T.ok(this.repository.allByFilter(");

		for (int i = 0; i < fields.length; i++) {
			String field = fields[i];
			builder.append(field);
			if (i < fields.length - 1)
				builder.append(", ");
		}

		if (this.pagination)
			builder.append(", pageable");

		builder.append("))");
		return builder.toString();
	}

	private MethodSpec.Builder addArguments(MethodSpec.Builder builder, String[] fields) {

		Arrays.stream(fields)
			.forEach(field -> {
			TypeMirror fieldType = this.eleUtils.getEnclosedElement(field).asType();

			builder.addParameter(
			    this.parBuilder.name(field)
			    .type(fieldType)
			    .build());
			});

		return builder;
	}

	private void addPageability(MethodSpec.Builder builder) {

		builder.addParameter(this.parBuilder.name("page").type(Integer.class).build())
			.addParameter(this.parBuilder.name("pageSize").type(Integer.class).build());

		builder.addStatement("$T pageable", Pageable.class)
			.beginControlFlow("if (page == null || pageSize == null)")
			.addStatement("pageable = $T.of(0, $T.MAX_VALUE)", PageRequest.class, Integer.class)
			.nextControlFlow("else")
			.addStatement("pageable = $T.of(page, pageSize)", PageRequest.class)
			.endControlFlow();
	}


	private MethodSpec.Builder createMethod(String name) {

		return MethodSpec.methodBuilder(name)
			.addModifiers(Modifier.PUBLIC)
			.returns(ResponseEntity.class);
	}
}
