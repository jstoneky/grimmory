import { SidebarSection } from '../../shared/layout/navigation/nav-item.model';

export interface AcquisitionNavPermissions {
  admin?: boolean;
  canManageAcquisition?: boolean;
}

type TranslateFn = (key: string) => string;

export function buildAcquisitionSection(
  translate: TranslateFn,
  permissions: AcquisitionNavPermissions,
): SidebarSection[] {
  if (!permissions.admin && !permissions.canManageAcquisition) return [];

  return [
    {
      id: 'acquisition',
      menuKey: 'acquisition',
      label: translate('layout.menu.acquisition'),
      expandable: true,
      items: [
        {
          id: 'discover',
          label: translate('layout.menu.discover'),
          icon: 'pi-search',
          routerLink: ['/discover'],
        },
        {
          id: 'wanted',
          label: translate('layout.menu.wanted'),
          icon: 'pi-bookmark',
          routerLink: ['/wanted'],
        },
      ],
    },
  ];
}
