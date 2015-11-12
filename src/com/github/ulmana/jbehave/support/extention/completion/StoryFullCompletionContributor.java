package com.github.ulmana.jbehave.support.extention.completion;

import com.github.kumaraman21.intellijbehave.completion.StoryCompletionContributor;
import com.github.kumaraman21.intellijbehave.highlighter.StoryTokenType;
import com.github.kumaraman21.intellijbehave.parser.JBehaveStep;
import com.github.kumaraman21.intellijbehave.resolver.StepDefinitionAnnotation;
import com.github.kumaraman21.intellijbehave.resolver.StepDefinitionAnnotationConverter;
import com.github.kumaraman21.intellijbehave.resolver.StepPsiReference;
import com.github.kumaraman21.intellijbehave.utility.LocalizedStorySupport;
import com.github.kumaraman21.intellijbehave.utility.ParametrizedString;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.java.stubs.index.JavaAnnotationIndex;
import com.intellij.psi.impl.java.stubs.index.JavaFullClassNameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.Consumer;
import org.jbehave.core.annotations.Given;
import org.jbehave.core.annotations.Then;
import org.jbehave.core.annotations.When;
import org.jbehave.core.i18n.LocalizedKeywords;
import org.jbehave.core.steps.StepType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;

/**
 * @author <a href="http://twitter.com/aloyer">@aloyer</a>
 */
public class StoryFullCompletionContributor extends StoryCompletionContributor {
  public StoryFullCompletionContributor() {
  }

  @Override
  public void fillCompletionVariants(CompletionParameters parameters, final CompletionResultSet _result) {
    if (parameters.getCompletionType() == CompletionType.BASIC) {
      String prefix = CompletionUtil.findReferenceOrAlphanumericPrefix(parameters);
      CompletionResultSet result = _result.withPrefixMatcher(prefix);

      LocalizedKeywords keywords = lookupLocalizedKeywords(parameters);
      Consumer<LookupElement> consumer = newConsumer(_result);

      addAllKeywords(result.getPrefixMatcher(), consumer, keywords);
      addAllSteps(parameters, result.getPrefixMatcher(), consumer, keywords);
    }
  }

  private static LocalizedKeywords lookupLocalizedKeywords(CompletionParameters parameters) {
    String locale = "en";
    ASTNode localeNode = parameters.getOriginalFile().getNode().findChildByType(StoryTokenType.COMMENT_WITH_LOCALE);
    if (localeNode != null) {
      String localeFound = LocalizedStorySupport.checkForLanguageDefinition(localeNode.getText());
      if (localeFound != null) {
        locale = localeFound;
      }
    }
    return new LocalizedStorySupport().getKeywords(locale);
  }

  private static Consumer<LookupElement> newConsumer(final CompletionResultSet result) {
    return new Consumer<LookupElement>() {
      @Override
      public void consume(LookupElement element) {
        result.addElement(element);
      }
    };
  }

  private static void addAllKeywords(PrefixMatcher prefixMatcher, Consumer<LookupElement> consumer, LocalizedKeywords keywords) {
    addIfMatches(consumer, prefixMatcher, keywords.narrative());
    addIfMatches(consumer, prefixMatcher, keywords.asA());
    addIfMatches(consumer, prefixMatcher, keywords.inOrderTo());
    addIfMatches(consumer, prefixMatcher, keywords.iWantTo());
    //
    addIfMatches(consumer, prefixMatcher, keywords.givenStories());
    addIfMatches(consumer, prefixMatcher, keywords.ignorable());
    addIfMatches(consumer, prefixMatcher, keywords.scenario());
    addIfMatches(consumer, prefixMatcher, keywords.examplesTable());
    //
    addIfMatches(consumer, prefixMatcher, keywords.given());
    addIfMatches(consumer, prefixMatcher, keywords.when());
    addIfMatches(consumer, prefixMatcher, keywords.then());
    addIfMatches(consumer, prefixMatcher, keywords.and());
  }

  private static void addIfMatches(Consumer<LookupElement> consumer, PrefixMatcher prefixMatchers, String input) {
    if (prefixMatchers.prefixMatches(input)) {
      consumer.consume(LookupElementBuilder.create(input));
    }
  }

  private static void addAllSteps(CompletionParameters parameters,
                                  PrefixMatcher prefixMatcher,
                                  Consumer<LookupElement> consumer,
                                  LocalizedKeywords keywords) {
    JBehaveStep step = getStepPsiElement(parameters);
    if (step == null) {
      return;
    }

    StepType stepType = step.getStepType();
    String actualStepPrefix = step.getActualStepPrefix();
    String textBeforeCaret = CompletionUtil.findReferenceOrAlphanumericPrefix(parameters);

    // suggest only if at least the actualStepPrefix is complete
    if (isStepTypeComplete(keywords, textBeforeCaret)) {
      StepSuggester stepAnnotationFinder = new StepSuggester(prefixMatcher, stepType, actualStepPrefix, textBeforeCaret, consumer);
      stepAnnotationFinder.consumeAnnotations(step);
    }
  }

  private static boolean isStepTypeComplete(LocalizedKeywords keywords, String input) {
    String tail = input.contains("\n") ? input.substring(input.indexOf('\n') + 1) : input;
    return tail.startsWith(keywords.given()) ||
           tail.startsWith(keywords.when()) ||
           tail.startsWith(keywords.then()) ||
           tail.startsWith(keywords.and());
  }

  private static JBehaveStep getStepPsiElement(CompletionParameters parameters) {
    PsiElement position = parameters.getPosition();
    PsiElement positionParent = position.getParent();
    if (positionParent instanceof JBehaveStep) {
      return (JBehaveStep)positionParent;
    }
    else if (position instanceof StepPsiReference) {
      return ((StepPsiReference)position).getElement();
    }
    else if (position instanceof JBehaveStep) {
      return (JBehaveStep)position;
    }
    else {
      return null;
    }
  }

  private static class StepSuggester {

    private final PrefixMatcher prefixMatcher;
    private final String actualStepPrefix;
    private final String textBeforeCaret;
    private final Consumer<LookupElement> consumer;
    private final StepDefinitionAnnotationConverter stepDefinitionAnnotationConverter = new StepDefinitionAnnotationConverter();
    private StepType stepType;


    private StepSuggester(PrefixMatcher prefixMatcher,
                          StepType stepType,
                          String actualStepPrefix,
                          String textBeforeCaret,
                          Consumer<LookupElement> consumer) {
      this.stepType = stepType;
      this.prefixMatcher = prefixMatcher;
      this.actualStepPrefix = actualStepPrefix;
      this.textBeforeCaret = textBeforeCaret;
      this.consumer = consumer;
    }

    public boolean consumeAnnotations(JBehaveStep step) {
      Module module = ModuleUtilCore.findModuleForPsiElement(step);
      List<StepDefinitionAnnotation> annotations = loadStepsFor(module);
      for (StepDefinitionAnnotation annotation : annotations) {
        processStepDefinition(annotation);
      }
      return true;
    }

    public StepType getStepType() {
      return stepType;
    }

    private boolean processStepDefinition(@NotNull final StepDefinitionAnnotation stepDefinitionAnnotation) {
      StepType annotationStepType = stepDefinitionAnnotation.getStepType();
      if (annotationStepType != getStepType()) {
        return true;
      }
      String annotationText = stepDefinitionAnnotation.getAnnotationText();
      String adjustedAnnotationText = actualStepPrefix + " " + annotationText;

      ParametrizedString pString = new ParametrizedString(adjustedAnnotationText);
      String complete = pString.complete(textBeforeCaret);
      if (StringUtil.isNotEmpty(complete)) {
        PsiAnnotation matchingAnnotation = stepDefinitionAnnotation.getAnnotation();
        consumer.consume(LookupElementBuilder.create(matchingAnnotation, textBeforeCaret + complete));
      }
      else if (prefixMatcher.prefixMatches(adjustedAnnotationText)) {
        PsiAnnotation matchingAnnotation = stepDefinitionAnnotation.getAnnotation();
        consumer.consume(LookupElementBuilder.create(matchingAnnotation, adjustedAnnotationText));
      }
      return true;
    }


    private List<StepDefinitionAnnotation> loadStepsFor(final Module module) {
      if (module == null) {
        return emptyList();
      }
      GlobalSearchScope dependenciesScope = module.getModuleWithDependenciesAndLibrariesScope(true);

      PsiClass givenAnnotationClass = findStepAnnotation(Given.class.getName(), module, dependenciesScope);
      PsiClass whenAnnotationClass = findStepAnnotation(When.class.getName(), module, dependenciesScope);
      PsiClass thenAnnotationClass = findStepAnnotation(Then.class.getName(), module, dependenciesScope);

      if (givenAnnotationClass == null || whenAnnotationClass == null || thenAnnotationClass == null) {
        return emptyList();
      }

      List<StepDefinitionAnnotation> result = new ArrayList<StepDefinitionAnnotation>();

      List<PsiClass> stepAnnotations = asList(givenAnnotationClass, whenAnnotationClass, thenAnnotationClass);

      for (PsiClass annotationClass : stepAnnotations) {
        Collection<PsiAnnotation> allStepAnnotations = getAllStepAnnotations(annotationClass, dependenciesScope);
        result
          .addAll(stepDefinitionAnnotationConverter.convertFrom(allStepAnnotations.toArray(new PsiAnnotation[allStepAnnotations.size()])));

      }
      return result;
    }


    @NotNull
    private static Collection<PsiAnnotation> getAllStepAnnotations(@NotNull final PsiClass annClass,
                                                                   @NotNull final GlobalSearchScope scope) {
      return ApplicationManager.getApplication().runReadAction(new Computable<Collection<PsiAnnotation>>() {
        @Override
        public Collection<PsiAnnotation> compute() {
          Project project = annClass.getProject();
          Collection<PsiAnnotation> psiAnnotations = new ArrayList<PsiAnnotation>();
          psiAnnotations.addAll(JavaAnnotationIndex.getInstance().get(annClass.getName(), project, scope));
          return psiAnnotations;
        }
      });
    }


    private static PsiClass findStepAnnotation(@NotNull final String stepClass,
                                               @NotNull final Module module,
                                               @NotNull final GlobalSearchScope dependenciesScope) {
      Collection<PsiClass> stepDefAnnotationCandidates =
        JavaFullClassNameIndex.getInstance().get(stepClass.hashCode(), module.getProject(), dependenciesScope);

      for (PsiClass stepDefAnnotations : stepDefAnnotationCandidates) {
        if (stepClass.equals(stepDefAnnotations.getQualifiedName())) {
          return stepDefAnnotations;
        }
      }

      return null;
    }


  }
}
