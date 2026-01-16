#!/bin/bash
# Release script for Hytale mod projects
# Usage: ./tools/release.sh <patch|minor|major> <project1[,project2,...]>
#   Examples:
#     ./tools/release.sh patch rcon
#     ./tools/release.sh minor playground
#     ./tools/release.sh patch rcon,playground  (release multiple with same version bump)

set -e

# Get script directory and source logger
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
. "$SCRIPT_DIR/common/logger.sh"

# Validate release type argument
RELEASE_TYPE="${1:-}"
if [[ ! "$RELEASE_TYPE" =~ ^(patch|minor|major)$ ]]; then
    log_error "Invalid release type: '$RELEASE_TYPE'"
    log_info "Usage: $0 <patch|minor|major> <project1[,project2,...]>"
    log_info "Examples:"
    log_info "  $0 patch rcon"
    log_info "  $0 minor playground"
    log_info "  $0 patch rcon,playground"
    exit 1
fi

# Validate project argument (required, no defaults)
PROJECTS_STR="${2:-}"
if [ -z "$PROJECTS_STR" ]; then
    log_error "Project argument is required"
    log_info "Usage: $0 <patch|minor|major> <project1[,project2,...]>"
    log_info "Examples:"
    log_info "  $0 patch rcon"
    log_info "  $0 minor playground"
    log_info "  $0 patch rcon,playground"
    exit 1
fi

IFS=',' read -ra PROJECTS <<< "$PROJECTS_STR"

# Validate projects exist
for project in "${PROJECTS[@]}"; do
    if [ ! -d "$PROJECT_ROOT/projects/$project" ]; then
        error_exit "Project not found: $project (expected in projects/$project/)"
    fi
    if [ ! -f "$PROJECT_ROOT/projects/$project/build.gradle.kts" ]; then
        error_exit "Project build file not found: projects/$project/build.gradle.kts"
    fi
done

log_section "Release Process: $RELEASE_TYPE"
log_info "Projects: ${PROJECTS[*]}"

# Step 1: Run spotless check
log_step "1" "Checking code formatting (spotless)"
cd "$PROJECT_ROOT"
if ! ./gradlew spotlessCheck >/dev/null 2>&1; then
    log_error "Spotless check failed - code needs formatting"
    log_info "Run: ./gradlew spotlessApply"
    exit 1
fi
log_success "Code formatting is clean"

# Step 2: Check git is clean
log_step "2" "Checking git working directory"
if [ -n "$(git status --porcelain)" ]; then
    log_error "Git working directory is not clean"
    log_info "Commit or stash your changes before releasing"
    exit 1
fi
log_success "Git working directory is clean"

# Function to read version from a project's build.gradle.kts
read_project_version() {
    local project="$1"
    local gradle_file="$PROJECT_ROOT/projects/$project/build.gradle.kts"
    
    if [ ! -f "$gradle_file" ]; then
        error_exit "Build file not found: $gradle_file"
    fi
    
    local version=$(grep -E '^\s*version\s*=' "$gradle_file" | sed -E 's/.*version\s*=\s*"([^"]+)".*/\1/')
    if [ -z "$version" ]; then
        error_exit "Could not parse version from $gradle_file"
    fi
    
    echo "$version"
}

# Function to calculate new version
calculate_new_version() {
    local current_version="$1"
    local release_type="$2"
    
    IFS='.' read -ra VERSION_PARTS <<< "$current_version"
    local major=${VERSION_PARTS[0]:-0}
    local minor=${VERSION_PARTS[1]:-0}
    local patch=${VERSION_PARTS[2]:-0}
    
    case "$release_type" in
        major)
            major=$((major + 1))
            minor=0
            patch=0
            ;;
        minor)
            minor=$((minor + 1))
            patch=0
            ;;
        patch)
            patch=$((patch + 1))
            ;;
    esac
    
    echo "$major.$minor.$patch"
}

# Function to update version in a project
update_project_version() {
    local project="$1"
    local old_version="$2"
    local new_version="$3"
    
    local gradle_file="$PROJECT_ROOT/projects/$project/build.gradle.kts"
    local manifest_file="$PROJECT_ROOT/projects/$project/app/src/main/resources/manifest.json"
    
    # Update build.gradle.kts
    sed -i.bak "s/version = \"$old_version\"/version = \"$new_version\"/" "$gradle_file"
    rm -f "$gradle_file.bak"
    log_success "Updated $gradle_file"
    
    # Update manifest.json if it exists
    if [ -f "$manifest_file" ]; then
        if command_exists jq; then
            jq ".Version = \"$new_version\"" "$manifest_file" > "$manifest_file.tmp" && \
            mv "$manifest_file.tmp" "$manifest_file"
        else
            sed -i.bak "s/\"Version\": \"$old_version\"/\"Version\": \"$new_version\"/" "$manifest_file"
            rm -f "$manifest_file.bak"
        fi
        log_success "Updated $manifest_file"
    else
        log_warn "Manifest not found: $manifest_file (skipping)"
    fi
}

# Step 3: Read current versions and calculate new versions
log_step "3" "Reading current versions and calculating new versions"

declare -A CURRENT_VERSIONS
declare -A NEW_VERSIONS

for project in "${PROJECTS[@]}"; do
    current_version=$(read_project_version "$project")
    CURRENT_VERSIONS["$project"]="$current_version"
    new_version=$(calculate_new_version "$current_version" "$RELEASE_TYPE")
    NEW_VERSIONS["$project"]="$new_version"
    
    log_info "$project: $current_version → $new_version"
done

# Step 4: Update versions in all project files
log_step "4" "Updating versions in project files"

for project in "${PROJECTS[@]}"; do
    update_project_version "$project" "${CURRENT_VERSIONS[$project]}" "${NEW_VERSIONS[$project]}"
done

# Step 5: Build projects
log_step "5" "Building projects"

# Build specific projects or all
if [ ${#PROJECTS[@]} -eq 1 ]; then
    # Build single project
    if ! ./gradlew ":${PROJECTS[0]}:build" >/dev/null 2>&1; then
        log_error "Build failed for ${PROJECTS[0]}"
        log_warn "Version has been updated but not committed. You may need to revert changes."
        exit 1
    fi
else
    # Build all specified projects
    build_tasks=""
    for project in "${PROJECTS[@]}"; do
        build_tasks="$build_tasks :$project:build"
    done
    if ! ./gradlew $build_tasks >/dev/null 2>&1; then
        log_error "Build failed"
        log_warn "Version has been updated but not committed. You may need to revert changes."
        exit 1
    fi
fi
log_success "Build completed successfully"

# Step 6: Run tests
log_step "6" "Running tests"

if [ ${#PROJECTS[@]} -eq 1 ]; then
    # Test single project
    if ! ./gradlew ":${PROJECTS[0]}:test" >/dev/null 2>&1; then
        log_error "Tests failed for ${PROJECTS[0]}"
        log_warn "Version has been updated but not committed. You may need to revert changes."
        exit 1
    fi
else
    # Test all specified projects
    test_tasks=""
    for project in "${PROJECTS[@]}"; do
        test_tasks="$test_tasks :$project:test"
    done
    if ! ./gradlew $test_tasks >/dev/null 2>&1; then
        log_error "Tests failed"
        log_warn "Version has been updated but not committed. You may need to revert changes."
        exit 1
    fi
fi
log_success "All tests passed"

# Step 7: Commit version bumps
log_step "7" "Committing version bumps"

# Collect all changed files
changed_files=()
for project in "${PROJECTS[@]}"; do
    changed_files+=("$PROJECT_ROOT/projects/$project/build.gradle.kts")
    manifest_file="$PROJECT_ROOT/projects/$project/app/src/main/resources/manifest.json"
    [ -f "$manifest_file" ] && changed_files+=("$manifest_file")
done

git add "${changed_files[@]}" 2>/dev/null || true

# Create commit message
if [ ${#PROJECTS[@]} -eq 1 ]; then
    commit_msg="chore(${PROJECTS[0]}): bump version to ${NEW_VERSIONS[${PROJECTS[0]}]}"
else
    versions_str=""
    for project in "${PROJECTS[@]}"; do
        versions_str="$versions_str $project:${NEW_VERSIONS[$project]}"
    done
    commit_msg="chore: bump versions$versions_str"
fi

git commit -m "$commit_msg" >/dev/null 2>&1 || {
    log_error "Failed to commit version bump"
    exit 1
}
log_success "Version bump committed"

# Step 8: Create tags
log_step "8" "Creating release tags"

for project in "${PROJECTS[@]}"; do
    tag_name="${project}-v${NEW_VERSIONS[$project]}"
    
    # Check if tag already exists
    if git rev-parse "$tag_name" >/dev/null 2>&1; then
        log_warn "Tag $tag_name already exists"
        read -p "Overwrite existing tag? (y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            log_info "Tag creation cancelled for $project"
            continue
        fi
        git tag -d "$tag_name" 2>/dev/null || true
        git push origin ":refs/tags/$tag_name" 2>/dev/null || true
    fi
    
    git tag -a "$tag_name" -m "Release $project $tag_name"
    log_success "Tag $tag_name created"
done

# Step 9: Summary
log_section "Release Complete"

for project in "${PROJECTS[@]}"; do
    log_success "$project: ${CURRENT_VERSIONS[$project]} → ${NEW_VERSIONS[$project]}"
    log_success "Tag created: ${project}-v${NEW_VERSIONS[$project]}"
done

log_info ""
log_info "Next steps:"
log_info "  git push origin HEAD"

for project in "${PROJECTS[@]}"; do
    log_info "  git push origin ${project}-v${NEW_VERSIONS[$project]}"
done

log_info ""
log_info "Or push all at once:"
push_cmd="git push origin HEAD"
for project in "${PROJECTS[@]}"; do
    push_cmd="$push_cmd && git push origin ${project}-v${NEW_VERSIONS[$project]}"
done
log_info "  $push_cmd"
