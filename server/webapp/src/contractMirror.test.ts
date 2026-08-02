import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const REPO = path.resolve(process.cwd(), '../..');
const KOTLIN_CONTRACT = path.join(
  REPO,
  'server-contract/src/commonMain/kotlin/com/buco7854/opentv/contract',
);
const TS_CONTRACT = [
  path.join(REPO, 'server/webapp/src/api.ts'),
  path.join(REPO, 'server/webapp/src/admin/api.ts'),
  path.join(REPO, 'server/webapp/src/auth/types.ts'),
];

/**
 * Pairs every named TypeScript server shape with the Kotlin wire DTO that owns
 * its JSON keys. Several Kotlin page DTOs intentionally share ListingPage.
 */
const MIRRORS: ReadonlyArray<readonly [string, string]> = [
  ['MessageDto', 'Message'],
  ['WatchIntentPeer', 'WatchIntentPeer'],
  ['WatchIntentResponse', 'WatchIntent'],
  ['ClientCapabilitiesDto', 'ClientCapabilities'],
  ['PlaybackCreateRequest', 'PlaybackCreateRequest'],
  ['PlaybackLeaseDto', 'PlaybackLease'],
  ['WebSocketAccessDto', 'MediaGrant'],
  ['MediaGrantDto', 'MediaGrant'],
  ['ServerInfoDto', 'ServerInfoDto'],
  ['RemuxAvailableDto', 'RemuxAvailable'],
  ['RemuxStartDto', 'RemuxStart'],
  ['PlaylistUpsertRequest', 'PlaylistUpsertRequest'],
  ['PlaylistUpdateRequest', 'PlaylistUpdateRequest'],
  ['PlaylistDto', 'Playlist'],
  ['PlaylistEditDto', 'PlaylistEdit'],
  ['PlaylistRefreshResultDto', 'PlaylistRefreshResult'],
  ['PlaylistRefreshJobDto', 'PlaylistRefreshJob'],
  ['PlaylistDeleteInfoDto', 'PlaylistDeleteInfo'],
  ['PlaylistDetailDto', 'PlaylistDetail'],
  ['PlaylistCapabilitiesDto', 'PlaylistCapabilities'],
  ['PlaylistOperationCapabilityDto', 'PlaylistOperationCapability'],
  ['ChannelPageDto', 'ListingPage'],
  ['SeriesGroupPageDto', 'ListingPage'],
  ['XtreamSeriesPageDto', 'ListingPage'],
  ['EpisodePageDto', 'EpisodePage'],
  ['SeriesHitDto', 'SeriesHit'],
  ['SearchResultsDto', 'SearchResults'],
  ['XtreamSeriesDetailDto', 'XtreamSeriesDetail'],
  ['FavoritesResolvedDto', 'FavoritesResolved'],
  ['UserFavoriteSeriesDto', 'UserFavoriteSeries'],
  ['UserFavoritesResolvedDto', 'UserFavoritesResolved'],
  ['SettingsDto', 'Settings'],
  ['SessionHeartbeatDto', 'SessionHeartbeat'],
  ['SyncStateDto', 'SyncState'],
  ['RoomMemberDto', 'RoomMember'],
  ['SessionCommandDto', 'SessionCommand'],
  ['HeartbeatResponseDto', 'HeartbeatResponse'],
  ['RemuxDiagDto', 'RemuxDiag'],
  ['SessionStreamDto', 'SessionStream'],
  ['SessionDto', 'Session'],
  ['ChannelDto', 'Channel'],
  ['ChannelListItemDto', 'ChannelListItem'],
  ['ChannelDecorationRequestDto', 'ChannelDecorationRequest'],
  ['EpisodeListItemDto', 'EpisodeListItem'],
  ['MetadataDto', 'Metadata'],
  ['FavoriteDto', 'Favorite'],
  ['ResumePointDto', 'ResumePoint'],
  ['DownloadDto', 'Download'],
  ['AdminDownloadDto', 'AdminDownload'],
  ['AdminBlobCancellationDto', 'AdminBlobCancellation'],
  ['GroupCountDto', 'GroupCount'],
  ['SeriesGroupDto', 'SeriesGroup'],
  ['XtreamSeriesDto', 'XtreamSeries'],
  ['XtreamSeriesListItemDto', 'XtreamSeriesListItem'],
  ['ProgrammeDto', 'Programme'],
  ['GuideEntryDto', 'GuideEntry'],
  ['AccountInfoDto', 'AccountInfo'],
  ['AuthCapabilitiesDto', 'AuthCapabilities'],
  ['AuthFlowDto', 'AuthFlow'],
  ['TotpEnrollmentDto', 'TotpEnrollment'],
  ['TotpStatusDto', 'TotpStatus'],
  ['CurrentUserDto', 'CurrentUser'],
  ['AuthSessionDto', 'AdminAuthSession'],
  ['AdminUserDto', 'AdminUser'],
  ['CreatedUserDto', 'CreatedUser'],
  ['UpdateUserRequestDto', 'AdminUserUpdate'],
  ['ResetUserDto', 'ResetUser'],
  ['PlaylistIdsDto', 'PlaylistIds'],
  ['AdminResumeDto', 'AdminResumePoint'],
  ['PendingOidcDto', 'PendingOidcIdentity'],
  ['WebAuthnRpDto', 'WebAuthnRp'],
  ['WebAuthnUserDto', 'WebAuthnUser'],
  ['WebAuthnAlgorithmDto', 'WebAuthnAlgorithm'],
  ['WebAuthnDescriptorDto', 'WebAuthnDescriptor'],
  ['WebAuthnSelectionDto', 'WebAuthnSelection'],
  ['WebAuthnOptionsDto', 'WebAuthnOptions'],
  ['WebAuthnCredentialDto', 'WebAuthnCredential'],
  ['DeviceLinkStartRequestDto', 'DeviceLinkStartRequest'],
  ['DeviceLinkStartDto', 'DeviceLinkStart'],
  ['DeviceLinkPreviewDto', 'DeviceLinkPreview'],
  ['DeviceLinkStatusDto', 'DeviceLinkStatus'],
  ['DeviceLinkLookupDto', 'DeviceLinkRequest'],
];

const PROVIDER_ID_MIRRORS: ReadonlyArray<readonly [string, string, string]> = [
  ['ChannelDto', 'Channel', 'xtreamStreamId'],
  ['ChannelListItemDto', 'ChannelListItem', 'xtreamStreamId'],
  ['XtreamSeriesDto', 'XtreamSeries', 'seriesId'],
  ['XtreamSeriesListItemDto', 'XtreamSeriesListItem', 'seriesId'],
  ['SeriesHitDto', 'SeriesHit', 'xtreamSeriesId'],
  ['MetadataDto', 'Metadata', 'sourceId'],
];

const VOCABULARY_MIRRORS: ReadonlyArray<readonly [string, string]> = [
  ['PlaylistEditField', 'PlaylistEditField'],
  ['PlaylistEpgRefreshStatus', 'PlaylistEpgRefreshStatus'],
  ['PlaylistRefreshJobStatus', 'PlaylistRefreshJobStatus'],
  ['PlaylistDeleteEffect', 'PlaylistDeleteEffect'],
  ['PlaylistOperation', 'PlaylistOperation'],
  ['PlaylistOperationExecution', 'PlaylistOperationExecution'],
];

/**
 * These DTOs travel from the browser to Kotlin. Their defaulted/nullable Kotlin
 * fields may intentionally be omitted by TypeScript callers. Every other mirror
 * is server output, where encodeDefaults=true makes every field required.
 */
const INPUT_MIRRORS = new Set([
  'ClientCapabilitiesDto',
  'PlaybackCreateRequest',
  'PlaylistUpsertRequest',
  'PlaylistUpdateRequest',
  'UpdateUserRequestDto',
  'DeviceLinkStartRequestDto',
  'ChannelDecorationRequestDto',
]);

function matchingParen(source: string, open: number): number {
  let depth = 0;
  for (let i = open; i < source.length; i += 1) {
    if (source[i] === '(') depth += 1;
    if (source[i] === ')') {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  throw new Error(`Unclosed Kotlin constructor at offset ${open}`);
}

function matchingBrace(source: string, open: number): number {
  let depth = 0;
  for (let i = open; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  throw new Error(`Unclosed TypeScript interface at offset ${open}`);
}

function kotlinFields(): Map<string, string[]> {
  const result = new Map<string, string[]>();
  for (const file of fs.readdirSync(KOTLIN_CONTRACT).filter((name) => name.endsWith('.kt'))) {
    const source = fs.readFileSync(path.join(KOTLIN_CONTRACT, file), 'utf8');
    const declaration = /\bdata\s+class\s+(\w+)\s*\(/g;
    for (let match = declaration.exec(source); match; match = declaration.exec(source)) {
      const name = match[1];
      if (!name) continue;
      const open = source.indexOf('(', match.index);
      const close = matchingParen(source, open);
      const constructor = source.slice(open + 1, close)
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/\/\/.*$/gm, '');
      result.set(name, [...constructor.matchAll(/\bval\s+(\w+)\s*:/g)]
        .map((field) => field[1])
        .filter((field): field is string => field != null)
        .sort());
      declaration.lastIndex = close + 1;
    }
  }
  return result;
}

function typescriptFields(): Map<string, string[]> {
  const ownFields = new Map<string, string[]>();
  const bases = new Map<string, string>();
  for (const fileName of TS_CONTRACT) {
    const source = fs.readFileSync(fileName, 'utf8');
    const declaration = /\bexport\s+interface\s+(\w+)(?:<[^>{]+>)?(?:\s+extends\s+([^{]+))?\s*\{/g;
    for (let match = declaration.exec(source); match; match = declaration.exec(source)) {
      const name = match[1];
      if (!name) continue;
      const open = source.indexOf('{', match.index);
      const close = matchingBrace(source, open);
      const body = source.slice(open + 1, close)
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/\/\/.*$/gm, '');
      ownFields.set(name, [...body.matchAll(/\b(\w+)\??\s*:/g)]
        .map((field) => field[1])
        .filter((field): field is string => field != null));
      const base = match[2]?.trim().match(/^(\w+)/)?.[1];
      if (base) bases.set(name, base);
      declaration.lastIndex = close + 1;
    }
  }
  const result = new Map<string, string[]>();
  const resolve = (name: string): string[] => {
    const cached = result.get(name);
    if (cached) return cached;
    const parent = bases.get(name);
    const fields = [...(parent ? resolve(parent) : []), ...(ownFields.get(name) ?? [])].sort();
    result.set(name, fields);
    return fields;
  };
  ownFields.forEach((_, name) => resolve(name));
  return result;
}

function kotlinFieldTypes(): Map<string, Map<string, string>> {
  const result = new Map<string, Map<string, string>>();
  for (const file of fs.readdirSync(KOTLIN_CONTRACT).filter((name) => name.endsWith('.kt'))) {
    const source = fs.readFileSync(path.join(KOTLIN_CONTRACT, file), 'utf8');
    const declaration = /\bdata\s+class\s+(\w+)\s*\(/g;
    for (let match = declaration.exec(source); match; match = declaration.exec(source)) {
      const name = match[1];
      if (!name) continue;
      const open = source.indexOf('(', match.index);
      const close = matchingParen(source, open);
      const constructor = source.slice(open + 1, close)
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/\/\/.*$/gm, '');
      result.set(name, new Map(
        [...constructor.matchAll(/\bval\s+(\w+)\s*:\s*([^,=\n]+)/g)]
          .map((field) => [field[1]!, field[2]!.trim()]),
      ));
      declaration.lastIndex = close + 1;
    }
  }
  return result;
}

function typescriptFieldTypes(): Map<string, Map<string, string>> {
  const ownFields = new Map<string, Map<string, string>>();
  const bases = new Map<string, string>();
  for (const fileName of TS_CONTRACT) {
    const source = fs.readFileSync(fileName, 'utf8');
    const declaration = /\bexport\s+interface\s+(\w+)(?:<[^>{]+>)?(?:\s+extends\s+([^{]+))?\s*\{/g;
    for (let match = declaration.exec(source); match; match = declaration.exec(source)) {
      const name = match[1];
      if (!name) continue;
      const open = source.indexOf('{', match.index);
      const close = matchingBrace(source, open);
      const body = source.slice(open + 1, close)
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/\/\/.*$/gm, '');
      ownFields.set(name, new Map(
        [...body.matchAll(/\b(\w+)(\?)?\s*:\s*([^;\n]+)/g)]
          .map((field) => [
            field[1]!,
            `${field[3]!.trim()}${field[2] ? ' | undefined' : ''}`,
          ]),
      ));
      const base = match[2]?.trim().match(/^(\w+)/)?.[1];
      if (base) bases.set(name, base);
      declaration.lastIndex = close + 1;
    }
  }
  const result = new Map<string, Map<string, string>>();
  const resolve = (name: string): Map<string, string> => {
    const cached = result.get(name);
    if (cached) return cached;
    const fields = new Map(bases.get(name) ? resolve(bases.get(name)!) : []);
    ownFields.get(name)?.forEach((type, field) => fields.set(field, type));
    result.set(name, fields);
    return fields;
  };
  ownFields.forEach((_, name) => resolve(name));
  return result;
}

function kotlinVocabulary(name: string): string[] {
  for (const file of fs.readdirSync(KOTLIN_CONTRACT).filter((entry) => entry.endsWith('.kt'))) {
    const source = fs.readFileSync(path.join(KOTLIN_CONTRACT, file), 'utf8');
    const declaration = new RegExp(`\\bobject\\s+${name}\\s*\\{`).exec(source);
    if (!declaration) continue;
    const open = source.indexOf('{', declaration.index);
    const close = matchingBrace(source, open);
    return [...source.slice(open + 1, close).matchAll(/\bconst\s+val\s+\w+\s*=\s*"([^"]+)"/g)]
      .map((entry) => entry[1]!)
      .sort();
  }
  throw new Error(`Missing Kotlin vocabulary ${name}`);
}

function typescriptVocabulary(name: string): string[] {
  for (const fileName of TS_CONTRACT) {
    const source = fs.readFileSync(fileName, 'utf8');
    const declaration = new RegExp(`\\bexport\\s+const\\s+${name}\\s*=\\s*\\{`).exec(source);
    if (!declaration) continue;
    const open = source.indexOf('{', declaration.index);
    const close = matchingBrace(source, open);
    return [...source.slice(open + 1, close).matchAll(/:\s*['"]([^'"]+)['"]/g)]
      .map((entry) => entry[1]!)
      .sort();
  }
  throw new Error(`Missing TypeScript vocabulary ${name}`);
}

type WireTypeShape = {
  kind: 'boolean' | 'list' | 'number' | 'object' | 'string';
  nullable: boolean;
  required: boolean;
};

const TS_STRING_TYPES = new Set([
  'AdminUser[\'status\']',
  'AttestationConveyancePreference',
  'AuthStatus',
  'ClientKind',
  'DeviceLinkState',
  'DownloadStatus',
  'PlaylistEpgRefreshStatus',
  'PlaylistRefreshJobStatus',
  'PlaylistOperation',
  'PlaylistOperationExecution',
  'PlaylistMode',
  'ResidentKeyRequirement',
  'SessionCommandType',
  'SessionHeartbeat[\'kind\']',
  'UserRole',
  'UserVerificationRequirement',
]);

function kotlinWireShape(type: string): WireTypeShape {
  const trimmed = type.trim();
  const nullable = trimmed.endsWith('?');
  const base = nullable ? trimmed.slice(0, -1).trim() : trimmed;
  const kind = base.startsWith('List<') || base.startsWith('Set<')
    ? 'list'
    : base === 'Boolean'
      ? 'boolean'
      : /^(?:Byte|Short|Int|Long|Float|Double)$/.test(base)
        ? 'number'
        : base === 'String'
          ? 'string'
          : 'object';
  return { kind, nullable, required: true };
}

function typescriptWireShape(type: string): WireTypeShape {
  const nullable = /(?:^|\|)\s*null(?:\||$)/.test(type);
  const required = !/(?:^|\|)\s*undefined(?:\||$)/.test(type);
  const base = type
    .replace(/\s*\|\s*null\b/g, '')
    .replace(/\s*\|\s*undefined\b/g, '')
    .trim();
  const stringLiteralUnion = /^(?:'[^']+'|"[^"]+")(?:\s*\|\s*(?:'[^']+'|"[^"]+"))*$/
    .test(base);
  const kind = base.endsWith('[]') || base.startsWith('Array<')
    ? 'list'
    : base === 'boolean'
      ? 'boolean'
      : base === 'number' || /^-?\d+(?:\.\d+)?$/.test(base)
        ? 'number'
        : base === 'string' || stringLiteralUnion || TS_STRING_TYPES.has(base)
          ? 'string'
          : 'object';
  return { kind, nullable, required };
}

describe('hand-written server contract mirror', () => {
  it('keeps every named TypeScript JSON shape field-for-field with Kotlin', () => {
    const kotlin = kotlinFields();
    const typescript = typescriptFields();

    for (const [kotlinName, typescriptName] of MIRRORS) {
      expect(
        typescript.get(typescriptName),
        `${typescriptName} must mirror ${kotlinName}`,
      ).toEqual(kotlin.get(kotlinName));
    }
  });

  it('keeps closed wire vocabularies value-for-value with Kotlin', () => {
    for (const [kotlinName, typescriptName] of VOCABULARY_MIRRORS) {
      expect(
        typescriptVocabulary(typescriptName),
        `${typescriptName} must mirror every value in ${kotlinName}`,
      ).toEqual(kotlinVocabulary(kotlinName));
    }
  });

  it('keeps provider-controlled numeric identities precision-safe on both clients', () => {
    const kotlin = kotlinFieldTypes();
    const typescript = typescriptFieldTypes();

    for (const [kotlinName, typescriptName, field] of PROVIDER_ID_MIRRORS) {
      const nullable = field === 'xtreamStreamId'
        || field === 'xtreamSeriesId'
        || field === 'sourceId';
      expect(kotlin.get(kotlinName)?.get(field)).toBe(nullable ? 'String?' : 'String');
      expect(typescript.get(typescriptName)?.get(field))
        .toBe(nullable ? 'string | null' : 'string');
    }
  });

  it('keeps JSON scalar, object, array, nullability, and default-emission shapes aligned', () => {
    const kotlin = kotlinFieldTypes();
    const typescript = typescriptFieldTypes();

    for (const [kotlinName, typescriptName] of MIRRORS) {
      const kotlinFieldsForType = kotlin.get(kotlinName);
      const typescriptFieldsForType = typescript.get(typescriptName);
      expect(kotlinFieldsForType, `missing Kotlin type ${kotlinName}`).toBeDefined();
      expect(typescriptFieldsForType, `missing TypeScript type ${typescriptName}`).toBeDefined();

      for (const [field, kotlinType] of kotlinFieldsForType!) {
        const typescriptType = typescriptFieldsForType!.get(field);
        expect(
          typescriptType,
          `${typescriptName}.${field} must mirror ${kotlinName}.${field}`,
        ).toBeDefined();
        const kotlinShape = kotlinWireShape(kotlinType);
        const typescriptShape = typescriptWireShape(typescriptType!);
        expect(
          typescriptShape.kind,
          `${typescriptName}.${field} must keep ${kotlinName}.${field}'s JSON kind`,
        ).toBe(kotlinShape.kind);
        if (!INPUT_MIRRORS.has(kotlinName)) {
          expect(
            typescriptShape.nullable,
            `${typescriptName}.${field} must keep ${kotlinName}.${field}'s nullability`,
          ).toBe(kotlinShape.nullable);
          expect(
            typescriptShape.required,
            `${typescriptName}.${field} is emitted because server JSON enables defaults`,
          ).toBe(true);
        }
      }
    }
  });

  it('keeps now-airing keyed by tvg id across the route and both client contracts', () => {
    const service = fs.readFileSync(
      path.join(
        REPO,
        'server/src/main/kotlin/com/buco7854/opentv/server/PlaylistApplicationService.kt',
      ),
      'utf8',
    );
    const browserApi = fs.readFileSync(path.join(REPO, 'server/webapp/src/api.ts'), 'utf8');
    const hubApi = fs.readFileSync(
      path.join(
        REPO,
        'hub-client/src/commonMain/kotlin/com/buco7854/opentv/hub/HubApi.kt',
      ),
      'utf8',
    );

    expect(service).toMatch(
      /suspend fun nowAiring\([^)]*\):\s*Map<String,\s*ProgrammeDto>/,
    );
    expect(browserApi).toMatch(
      /nowAiring:\s*\(id:\s*number,\s*tvgIds:\s*string\[\]\)\s*=>\s*j<Record<string,\s*Programme>>/,
    );
    expect(hubApi).toMatch(
      /suspend fun nowAiring\([^)]*tvgIds:\s*List<String>[^)]*\):\s*List<ProgrammeDto>/,
    );
  });
});
