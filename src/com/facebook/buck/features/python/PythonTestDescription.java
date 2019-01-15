/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.features.python;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.HasContacts;
import com.facebook.buck.core.description.arg.HasTestTimeout;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.NeededCoverageSpec;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.versions.HasVersionUniverse;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.Resources;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Function;
import org.immutables.value.Value;

public class PythonTestDescription
    implements DescriptionWithTargetGraph<PythonTestDescriptionArg>,
        ImplicitDepsInferringDescription<PythonTestDescription.AbstractPythonTestDescriptionArg>,
        VersionRoot<PythonTestDescriptionArg> {

  private static final Flavor BINARY_FLAVOR = InternalFlavor.of("binary");

  private final ToolchainProvider toolchainProvider;
  private final PythonBinaryDescription binaryDescription;
  private final PythonBuckConfig pythonBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;

  public PythonTestDescription(
      ToolchainProvider toolchainProvider,
      PythonBinaryDescription binaryDescription,
      PythonBuckConfig pythonBuckConfig,
      CxxBuckConfig cxxBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.binaryDescription = binaryDescription;
    this.pythonBuckConfig = pythonBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  @Override
  public Class<PythonTestDescriptionArg> getConstructorArgType() {
    return PythonTestDescriptionArg.class;
  }

  @VisibleForTesting
  protected static Path getTestMainName() {
    return Paths.get("__test_main__.py");
  }

  @VisibleForTesting
  protected static Path getTestModulesListName() {
    return Paths.get("__test_modules__.py");
  }

  @VisibleForTesting
  protected static Path getTestModulesListPath(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s")
        .resolve(getTestModulesListName());
  }

  /**
   * Create the contents of a python source file that just contains a list of the given test
   * modules.
   */
  private static String getTestModulesListContents(ImmutableSet<String> modules) {
    StringBuilder contents = new StringBuilder("TEST_MODULES = [\n");
    for (String module : modules) {
      contents.append(String.format("    \"%s\",\n", module));
    }
    contents.append("]");
    return contents.toString();
  }

  /**
   * Return a {@link BuildRule} that constructs the source file which contains the list of test
   * modules this python test rule will run. Setting up a separate build rule for this allows us to
   * use the existing python binary rule without changes to account for the build-time creation of
   * this file.
   */
  private static BuildRule createTestModulesSourceBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      Path outputPath,
      ImmutableSet<String> testModules) {

    // Modify the build rule params to change the target, type, and remove all deps.
    buildTarget.assertUnflavored();
    BuildTarget newBuildTarget = buildTarget.withAppendedFlavors(InternalFlavor.of("test_module"));

    String contents = getTestModulesListContents(testModules);

    return new WriteFile(
        newBuildTarget, projectFilesystem, contents, outputPath, /* executable */ false);
  }

  private CxxPlatform getCxxPlatform(BuildTarget target, AbstractPythonTestDescriptionArg args) {
    CxxPlatformsProvider cxxPlatformsProvider =
        toolchainProvider.getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
    FlavorDomain<CxxPlatform> cxxPlatforms = cxxPlatformsProvider.getCxxPlatforms();

    return cxxPlatforms
        .getValue(target)
        .orElse(
            args.getCxxPlatform()
                .map(cxxPlatforms::getValue)
                .orElse(cxxPlatformsProvider.getDefaultCxxPlatform()));
  }

  private static class PythonTestMainRule extends AbstractBuildRule {
    private final Path output =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem(), getBuildTarget(), "%s/__test_main__.py");

    public PythonTestMainRule(BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
      super(buildTarget, projectFilesystem);
    }

    @Override
    public SortedSet<BuildRule> getBuildDeps() {
      return ImmutableSortedSet.of();
    }

    @Override
    public ImmutableList<? extends Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      buildableContext.recordArtifact(output);
      return ImmutableList.of(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())),
          new WriteFileStep(
              getProjectFilesystem(),
              Resources.asByteSource(
                  Resources.getResource(PythonTestDescription.class, "__test_main__.py")),
              output,
              /* executable */ false));
    }

    @Override
    public SourcePath getSourcePathToOutput() {
      return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
    }
  }

  private SourcePath requireTestMain(
      BuildTarget baseTarget, ProjectFilesystem filesystem, ActionGraphBuilder graphBuilder) {
    BuildRule testMainRule =
        graphBuilder.computeIfAbsent(
            baseTarget.withFlavors(InternalFlavor.of("python-test-main")),
            target -> new PythonTestMainRule(target, filesystem));
    return Objects.requireNonNull(testMainRule.getSourcePathToOutput());
  }

  @Override
  public PythonTest createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PythonTestDescriptionArg args) {

    FlavorDomain<PythonPlatform> pythonPlatforms =
        toolchainProvider
            .getByName(PythonPlatformsProvider.DEFAULT_NAME, PythonPlatformsProvider.class)
            .getPythonPlatforms();

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    PythonPlatform pythonPlatform =
        pythonPlatforms
            .getValue(buildTarget)
            .orElse(
                pythonPlatforms.getValue(
                    args.getPlatform()
                        .<Flavor>map(InternalFlavor::of)
                        .orElse(pythonPlatforms.getFlavors().iterator().next())));
    CxxPlatform cxxPlatform = getCxxPlatform(buildTarget, args);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    Path baseModule = PythonUtil.getBasePath(buildTarget, args.getBaseModule());
    Optional<ImmutableMap<BuildTarget, Version>> selectedVersions =
        context.getTargetGraph().get(buildTarget).getSelectedVersions();

    ImmutableMap<Path, SourcePath> srcs =
        PythonUtil.getModules(
            buildTarget,
            graphBuilder,
            ruleFinder,
            pathResolver,
            pythonPlatform,
            cxxPlatform,
            "srcs",
            baseModule,
            args.getSrcs(),
            args.getPlatformSrcs(),
            args.getVersionedSrcs(),
            selectedVersions);

    ImmutableMap<Path, SourcePath> resources =
        PythonUtil.getModules(
            buildTarget,
            graphBuilder,
            ruleFinder,
            pathResolver,
            pythonPlatform,
            cxxPlatform,
            "resources",
            baseModule,
            args.getResources(),
            args.getPlatformResources(),
            args.getVersionedResources(),
            selectedVersions);

    // Convert the passed in module paths into test module names.
    ImmutableSet.Builder<String> testModulesBuilder = ImmutableSet.builder();
    for (Path name : srcs.keySet()) {
      testModulesBuilder.add(PythonUtil.toModuleName(buildTarget, name.toString()));
    }
    ImmutableSet<String> testModules = testModulesBuilder.build();

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    // Construct a build rule to generate the test modules list source file and
    // add it to the build.
    BuildRule testModulesBuildRule =
        createTestModulesSourceBuildRule(
            buildTarget,
            projectFilesystem,
            getTestModulesListPath(buildTarget, projectFilesystem),
            testModules);
    graphBuilder.addToIndex(testModulesBuildRule);

    String mainModule;
    if (args.getMainModule().isPresent()) {
      mainModule = args.getMainModule().get();
    } else {
      mainModule = PythonUtil.toModuleName(buildTarget, getTestMainName().toString());
    }

    // Build up the list of everything going into the python test.
    PythonPackageComponents testComponents =
        PythonPackageComponents.of(
            ImmutableMap.<Path, SourcePath>builder()
                .put(getTestModulesListName(), testModulesBuildRule.getSourcePathToOutput())
                .put(
                    getTestMainName(),
                    requireTestMain(buildTarget, projectFilesystem, graphBuilder))
                .putAll(srcs)
                .build(),
            resources,
            ImmutableMap.of(),
            ImmutableMultimap.of(),
            args.getZipSafe());
    ImmutableList<BuildRule> deps =
        RichStream.from(
                PythonUtil.getDeps(
                    pythonPlatform, cxxPlatform, args.getDeps(), args.getPlatformDeps()))
            .concat(args.getNeededCoverage().stream().map(NeededCoverageSpec::getBuildTarget))
            .map(graphBuilder::getRule)
            .collect(ImmutableList.toImmutableList());

    CellPathResolver cellRoots = context.getCellPathResolver();
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellRoots)
            .setExpanders(PythonUtil.MACRO_EXPANDERS)
            .build();
    PythonPackageComponents allComponents =
        PythonUtil.getAllComponents(
            cellRoots,
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            ruleFinder,
            deps,
            testComponents,
            pythonPlatform,
            cxxBuckConfig,
            cxxPlatform,
            args.getLinkerFlags()
                .stream()
                .map(x -> macrosConverter.convert(x, graphBuilder))
                .collect(ImmutableList.toImmutableList()),
            pythonBuckConfig.getNativeLinkStrategy(),
            args.getPreloadDeps());

    // Build the PEX using a python binary rule with the minimum dependencies.
    buildTarget.assertUnflavored();
    PythonBinary binary =
        binaryDescription.createPackageRule(
            buildTarget.withAppendedFlavors(BINARY_FLAVOR),
            projectFilesystem,
            params,
            graphBuilder,
            ruleFinder,
            pythonPlatform,
            cxxPlatform,
            mainModule,
            args.getExtension(),
            allComponents,
            args.getBuildArgs(),
            args.getPackageStyle().orElse(pythonBuckConfig.getPackageStyle()),
            PythonUtil.getPreloadNames(graphBuilder, cxxPlatform, args.getPreloadDeps()));
    graphBuilder.addToIndex(binary);

    ImmutableList.Builder<Pair<Float, ImmutableSet<Path>>> neededCoverageBuilder =
        ImmutableList.builder();
    for (NeededCoverageSpec coverageSpec : args.getNeededCoverage()) {
      BuildRule buildRule = graphBuilder.getRule(coverageSpec.getBuildTarget());
      if (deps.contains(buildRule) && buildRule instanceof PythonLibrary) {
        PythonLibrary pythonLibrary = (PythonLibrary) buildRule;
        ImmutableSortedSet<Path> paths;
        if (coverageSpec.getPathName().isPresent()) {
          Path path =
              coverageSpec.getBuildTarget().getBasePath().resolve(coverageSpec.getPathName().get());
          if (!pythonLibrary
              .getPythonPackageComponents(pythonPlatform, cxxPlatform, graphBuilder)
              .getModules()
              .keySet()
              .contains(path)) {
            throw new HumanReadableException(
                "%s: path %s specified in needed_coverage not found in target %s",
                buildTarget, path, buildRule.getBuildTarget());
          }
          paths = ImmutableSortedSet.of(path);
        } else {
          paths =
              ImmutableSortedSet.copyOf(
                  pythonLibrary
                      .getPythonPackageComponents(pythonPlatform, cxxPlatform, graphBuilder)
                      .getModules()
                      .keySet());
        }
        neededCoverageBuilder.add(
            new Pair<>(coverageSpec.getNeededCoverageRatioPercentage() / 100.f, paths));
      } else {
        throw new HumanReadableException(
            "%s: needed_coverage requires a python library dependency. Found %s instead",
            buildTarget, buildRule);
      }
    }

    Function<BuildRuleResolver, ImmutableMap<String, Arg>> testEnv =
        (ruleResolverInner) ->
            ImmutableMap.copyOf(
                Maps.transformValues(args.getEnv(), x -> macrosConverter.convert(x, graphBuilder)));

    // Additional CXX Targets used to generate CXX coverage.
    ImmutableSet<UnflavoredBuildTarget> additionalCoverageTargets =
        RichStream.from(args.getAdditionalCoverageTargets())
            .map(target -> target.getUnflavoredBuildTarget())
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSortedSet<SourcePath> additionalCoverageSourcePaths =
        additionalCoverageTargets.isEmpty()
            ? ImmutableSortedSet.of()
            : binary
                .getRuntimeDeps(ruleFinder)
                .filter(
                    target -> additionalCoverageTargets.contains(target.getUnflavoredBuildTarget()))
                .map(target -> DefaultBuildTargetSourcePath.of(target))
                .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    
    // Generate and return the python test rule, which depends on the python binary rule above.
    return PythonTest.from(
        buildTarget,
        projectFilesystem,
        params,
        graphBuilder,
        testEnv,
        binary,
        args.getLabels(),
        neededCoverageBuilder.build(),
        additionalCoverageSourcePaths,
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(cxxBuckConfig.getDelegate().getDefaultTestRuleTimeoutMs()),
        args.getContacts(),
        args.getTestRunner().orElse("pyunit"));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractPythonTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // We need to use the C/C++ linker for native libs handling, so add in the C/C++ linker to
    // parse time deps.
    extraDepsBuilder.addAll(getCxxPlatform(buildTarget, constructorArg).getLd().getParseTimeDeps());

    if (constructorArg.getPackageStyle().orElse(pythonBuckConfig.getPackageStyle())
        == PythonBuckConfig.PackageStyle.STANDALONE) {
      Optionals.addIfPresent(pythonBuckConfig.getPexTarget(), extraDepsBuilder);
      Optionals.addIfPresent(pythonBuckConfig.getPexExecutorTarget(), extraDepsBuilder);
    }
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPythonTestDescriptionArg
      extends HasContacts, HasTestTimeout, PythonLibraryDescription.CoreArg, HasVersionUniverse {
    Optional<String> getMainModule();

    Optional<String> getPlatform();

    Optional<Flavor> getCxxPlatform();

    Optional<String> getExtension();

    Optional<PythonBuckConfig.PackageStyle> getPackageStyle();

    ImmutableSet<BuildTarget> getPreloadDeps();

    ImmutableList<StringWithMacros> getLinkerFlags();

    ImmutableList<NeededCoverageSpec> getNeededCoverage();

    ImmutableList<String> getBuildArgs();

    ImmutableMap<String, StringWithMacros> getEnv();

    // Addidtional CxxLibrary Targets for coverage check
    // When we use python to drive cxx modules (loaded as foo.so), we would like
    // to collect code coverage of foo.so as well. In this case, we to path
    // targets that builds foo.so so that buck can resolve its binary path and
    // export the downstream testing framework to consume
    ImmutableSet<BuildTarget> getAdditionalCoverageTargets();

    Optional<String> getTestRunner();
  }
}
