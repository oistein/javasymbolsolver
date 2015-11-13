package me.tomassetti.symbolsolver.resolution.javaparser.contexts;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import me.tomassetti.symbolsolver.model.declarations.MethodDeclaration;
import me.tomassetti.symbolsolver.model.declarations.TypeDeclaration;
import me.tomassetti.symbolsolver.model.declarations.ValueDeclaration;
import me.tomassetti.symbolsolver.model.invokations.MethodUsage;
import me.tomassetti.symbolsolver.model.resolution.*;
import me.tomassetti.symbolsolver.model.typesystem.ReferenceTypeUsageImpl;
import me.tomassetti.symbolsolver.model.typesystem.TypeParameterUsage;
import me.tomassetti.symbolsolver.model.typesystem.TypeUsage;
import me.tomassetti.symbolsolver.resolution.javaparser.JavaParserFacade;
import me.tomassetti.symbolsolver.resolution.javaparser.UnsolvedSymbolException;
import me.tomassetti.symbolsolver.reflectionmodel.ReflectionClassDeclaration;

import java.util.List;
import java.util.Optional;

public class MethodCallExprContext extends AbstractJavaParserContext<MethodCallExpr> {

    public MethodCallExprContext(MethodCallExpr wrappedNode, TypeSolver typeSolver) {
        super(wrappedNode, typeSolver);
    }

    @Override
    public Optional<TypeUsage> solveGenericType(String name, TypeSolver typeSolver) {
        if (!wrappedNode.getTypeArgs().isEmpty()) {
            throw new UnsupportedOperationException(name);
        }
        TypeUsage typeOfScope = JavaParserFacade.get(typeSolver).getType(wrappedNode.getScope());
        return typeOfScope.asReferenceTypeUsage().getGenericParameterByName(name);
    }

    private Optional<MethodUsage> solveMethodAsUsage(ReferenceTypeUsageImpl refType, String name, List<TypeUsage> parameterTypes, TypeSolver typeSolver, Context invokationContext) {
        Optional<MethodUsage> ref = refType.getTypeDeclaration().solveMethodAsUsage(name, parameterTypes, typeSolver, invokationContext, refType.parameters());
        if (ref.isPresent()) {
            MethodUsage methodUsage = ref.get();
            TypeUsage returnType = refType.replaceTypeParams(methodUsage.returnType());
            if (returnType != methodUsage.returnType()) {
                methodUsage = methodUsage.replaceReturnType(returnType);
            }
            for (int i = 0; i < methodUsage.getParamTypes().size(); i++) {
                TypeUsage replaced = refType.replaceTypeParams(methodUsage.getParamTypes().get(i));
                methodUsage = methodUsage.replaceParamType(i, replaced);
            }
            return Optional.of(methodUsage);
        } else {
            return ref;
        }
    }

    @Override
    public String toString() {
        return "MethodCallExprContext{}";
    }

    private Optional<MethodUsage> solveMethodAsUsage(TypeParameterUsage tp, String name, List<TypeUsage> parameterTypes, TypeSolver typeSolver, Context invokationContext) {
        for (TypeParameter.Bound bound : tp.asTypeParameter().getBounds(typeSolver)) {
            Optional<MethodUsage> methodUsage = solveMethodAsUsage(bound.getType(), name, parameterTypes, typeSolver, invokationContext);
            if (methodUsage.isPresent()) {
                return methodUsage;
            }
        }
        return Optional.empty();
    }

    private Optional<MethodUsage> solveMethodAsUsage(TypeUsage typeUsage, String name, List<TypeUsage> parameterTypes, TypeSolver typeSolver, Context invokationContext) {
        if (typeUsage instanceof ReferenceTypeUsageImpl) {
            return solveMethodAsUsage((ReferenceTypeUsageImpl) typeUsage, name, parameterTypes, typeSolver, invokationContext);
        } else if (typeUsage instanceof TypeParameterUsage) {
            return solveMethodAsUsage((TypeParameterUsage) typeUsage, name, parameterTypes, typeSolver, invokationContext);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public Optional<MethodUsage> solveMethodAsUsage(String name, List<TypeUsage> parameterTypes, TypeSolver typeSolver) {
        // TODO consider call of static methods
        if (wrappedNode.getScope() != null) {
            try {
                TypeUsage typeOfScope = JavaParserFacade.get(typeSolver).getType(wrappedNode.getScope());
                return solveMethodAsUsage(typeOfScope, name, parameterTypes, typeSolver, this);
            } catch (UnsolvedSymbolException e) {
                // ok, maybe it was instead a static access, so let's look for a type
                if (wrappedNode.getScope() instanceof NameExpr) {
                    String className = ((NameExpr) wrappedNode.getScope()).getName();
                    SymbolReference<TypeDeclaration> ref = solveType(className, typeSolver);
                    if (ref.isSolved()) {
                        SymbolReference<MethodDeclaration> m = ref.getCorrespondingDeclaration().solveMethod(name, parameterTypes);
                        if (m.isSolved()) {
                            return Optional.of(new MethodUsage(m.getCorrespondingDeclaration(), typeSolver));
                        } else {
                            throw new UnsolvedSymbolException(ref.getCorrespondingDeclaration().toString(), "Method '" + name + "' with parameterTypes " + parameterTypes);
                        }
                    } else {
                        throw e;
                    }
                } else {
                    throw e;
                }
            }
        } else {
            if (wrappedNode.getParentNode() instanceof MethodCallExpr) {
                MethodCallExpr parent = (MethodCallExpr) wrappedNode.getParentNode();
                if (parent.getScope() == wrappedNode) {
                    return getParent().getParent().solveMethodAsUsage(name, parameterTypes, typeSolver);
                }
            }
            Context parentContext = getParent();
            return parentContext.solveMethodAsUsage(name, parameterTypes, typeSolver);
        }
    }

    @Override
    public SymbolReference<? extends ValueDeclaration> solveSymbol(String name, TypeSolver typeSolver) {
        return getParent().solveSymbol(name, typeSolver);
    }

    @Override
    public Optional<Value> solveSymbolAsValue(String name, TypeSolver typeSolver) {
        Context parentContext = getParent();
        return parentContext.solveSymbolAsValue(name, typeSolver);
    }

    @Override
    public SymbolReference<MethodDeclaration> solveMethod(String name, List<TypeUsage> parameterTypes, TypeSolver typeSolver) {
        if (wrappedNode.getScope() != null) {
            TypeUsage typeOfScope = JavaParserFacade.get(typeSolver).getType(wrappedNode.getScope());
            if (typeOfScope.isWildcard()) {
                if (typeOfScope.asWildcard().isExtends() || typeOfScope.asWildcard().isSuper()) {
                    return typeOfScope.asWildcard().getBoundedType().asReferenceTypeUsage().solveMethod(name, parameterTypes);
                } else {
                    return new ReferenceTypeUsageImpl(new ReflectionClassDeclaration(Object.class, typeSolver), typeSolver).solveMethod(name, parameterTypes);
                }
            } else {
                return typeOfScope.asReferenceTypeUsage().solveMethod(name, parameterTypes);
            }
        } else {
            TypeUsage typeOfScope = JavaParserFacade.get(typeSolver).getTypeOfThisIn(wrappedNode);
            return typeOfScope.asReferenceTypeUsage().solveMethod(name, parameterTypes);
        }
    }
}