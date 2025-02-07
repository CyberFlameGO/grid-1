import angular from 'angular';
import template from './image-editor.html';
import './image-editor.css';

import {service} from './service';
import {imageService} from '../image/service';
import '../services/label';
import {imageAccessor} from '../services/image-accessor';
import {usageRightsEditor} from '../usage-rights/usage-rights-editor';
import {metadataTemplates} from "../metadata-templates/metadata-templates";
import {leases} from '../leases/leases';
import {archiver} from '../components/gr-archiver-status/gr-archiver-status';
import {collectionsApi} from '../services/api/collections-api';
import {rememberScrollTop} from '../directives/gr-remember-scroll-top';

export var imageEditor = angular.module('kahuna.edits.imageEditor', [
    service.name,
    imageService.name,
    "kahuna.services.label",
    imageAccessor.name,
    usageRightsEditor.name,
    archiver.name,
    collectionsApi.name,
    rememberScrollTop.name,
    leases.name,
    metadataTemplates.name
]);

imageEditor.controller('ImageEditorCtrl', [
    '$rootScope',
    '$scope',
    '$timeout',
    'editsService',
    'editsApi',
    'imageService',
    'labelService',
    'imageAccessor',
    'collections',
    'mediaApi',

    function($rootScope,
             $scope,
             $timeout,
             editsService,
             editsApi,
             imageService,
             labelService,
             imageAccessor,
             collections,
             mediaApi) {

    var ctrl = this;
    ctrl.canUndelete = false;
    ctrl.isDeleted = false;
    ctrl.displayMetadataTemplates = window._clientConfig.metadataTemplates !== undefined && window._clientConfig.metadataTemplates.length > 0;

    mediaApi.getSession().then(session => {
        if (ctrl.image.data.softDeletedMetadata !== undefined && (session.user.permissions.canDelete || session.user.email === ctrl.image.data.uploadedBy)) { ctrl.canUndelete = true; }
        if (ctrl.image.data.softDeletedMetadata !== undefined) { ctrl.isDeleted = true; }
    });

    editsService.canUserEdit(ctrl.image).then(editable => {
        ctrl.userCanEdit = editable;
    });
    ctrl.batchApplyUsageRights = batchApplyUsageRights;
    editsApi.getUsageRightsCategories()
        .then(cats => ctrl.categories = cats)
        .finally(() => updateUsageRightsCategory());
    ctrl.error = false;
    ctrl.saved = false;
    ctrl.saving = false;
    ctrl.showUsageRights = false;
    ctrl.status = ctrl.image.data.valid ? 'ready' : 'invalid';
    ctrl.usageRights = imageService(ctrl.image).usageRights;
    ctrl.invalidReasons = ctrl.image.data.invalidReasons;
    ctrl.systemName = window._clientConfig.systemName;

    ctrl.undelete = undelete;

    ctrl.imageAsArray = [ctrl.image];

    ctrl.addLabelToImages = labelService.batchAdd;
    ctrl.removeLabelFromImages = labelService.batchRemove;
    ctrl.labelAccessor = (image) => imageAccessor.readLabels(image).map(label => label.data);

    //TODO put collections in their own directive
    ctrl.addCollection = false;
    ctrl.addToCollection = addToCollection;
    ctrl.batchApplyCollections = batchApplyCollections;
    ctrl.collectionError = false;
    ctrl.confirmDelete = false;
    ctrl.getCollectionStyle = getCollectionStyle;
    ctrl.openCollectionTree = openCollectionTree;
    ctrl.removeImageFromCollection = removeImageFromCollection;
    ctrl.selectionMode = true;

    // TODO: Find a way to broadcast more selectively
    const batchApplyUsageRightsEvent = 'events:batch-apply:usage-rights';
    const batchApplyCollectionsEvent = 'events:batch-apply:collections';
    const batchRemoveCollectionsEvent = 'events:batch-remove:collections';

    const metadata = ctrl.image.data.userMetadata.data.metadata;
    const usageRights =  ctrl.image.data.userMetadata.data.usageRights;

    const offMetadataUpdateStart =
        editsService.on(metadata, 'update-start', () => ctrl.saving = true);

    const offMetadataUpdateEnd =
        editsService.on(metadata, 'update-end', onSave);

    const offMetadataUpdateError =
        editsService.on(metadata, 'update-error', onError);

    const offUsageRightsUpdateStart =
        editsService.on(usageRights, 'update-start', () => ctrl.saving = true);

    const offUsageRightsUpdateEnd =
        editsService.on(usageRights, 'update-end', onSave);

    const offUsageRightsUpdateError =
        editsService.on(usageRights, 'update-error', onError);

    $scope.$on('$destroy', () => {
        offMetadataUpdateStart();
        offMetadataUpdateEnd();
        offMetadataUpdateError();
        offUsageRightsUpdateStart();
        offUsageRightsUpdateEnd();
        offUsageRightsUpdateError();
    });

    ctrl.onMetadataTemplateApplied = () => {
      $scope.$broadcast('events:metadata-template:template-applied', {});

      ctrl.showUsageRights = false;
      ctrl.usageRightsUpdatedByTemplate = false;
    };

    ctrl.onMetadataTemplateCancelled = (metadata, usageRights) => {
      $scope.$broadcast('events:metadata-template:template-cancelled', { metadata });

      ctrl.usageRights.data = usageRights;
      ctrl.showUsageRights = false;
      ctrl.usageRightsUpdatedByTemplate = false;
    };

    ctrl.onMetadataTemplateSelected = (metadata, usageRights) => {
      $scope.$broadcast('events:metadata-template:template-selected', { metadata });

      ctrl.showUsageRights = false;
      ctrl.usageRightsUpdatedByTemplate = false;
      ctrl.usageRights.data = usageRights;

      if (ctrl.image.data.usageRights === undefined ||
        ctrl.image.data.usageRights.category !== usageRights.category) {
        ctrl.showUsageRights = true;
      }

      const originalUsageRights = ctrl.image.data.usageRights ? ctrl.image.data.usageRights : {};
      if (angular.equals(usageRights, originalUsageRights) === false) {
        ctrl.usageRightsUpdatedByTemplate = true;
      }
    };

    if (Boolean(ctrl.withBatch)) {
        $scope.$on(batchApplyUsageRightsEvent, (e, { data }) => {
            const image = ctrl.image;
            const resource = image.data.userMetadata.data.usageRights;
            editsService.update(resource, data, image);
        });
    }

    if (Boolean(ctrl.withBatch)) {
        $scope.$on(batchApplyCollectionsEvent, (e, { collections }) => {
            collections.forEach( ctrl.addToCollection );
        });

        $scope.$on(batchRemoveCollectionsEvent, () => {
            const collectionsOnImage = ctrl.image.data.collections;
            collectionsOnImage.forEach( ctrl.removeImageFromCollection );
        });
    }

    function onSave() {
        return ctrl.image.get().then(newImage => {
            ctrl.image = newImage;
            ctrl.imageAsArray = [newImage];
            ctrl.usageRights = imageService(ctrl.image).usageRights;
            updateUsageRightsCategory();
            ctrl.status = ctrl.image.data.valid ? 'ready' : 'invalid';
            ctrl.saving = false;

            ctrl.error = false;
            ctrl.saved = true;
            $timeout(() => ctrl.saved = false, 1000);
        }).
        // TODO: we could retry here again, but re-saving does that, and given
        // it's auto-save, it should be fine.
        then(() => onError);
    }

    function onError() {
        ctrl.saving = false;
        ctrl.error = true;
    }

    function updateUsageRightsCategory() {
        let category = ctrl.categories.find(cat => cat.value === ctrl.usageRights.data.category);
        ctrl.usageRightsCategory = category && category.name;
        ctrl.showUsageRights = ctrl.usageRightsCategory === undefined;
    }

    function batchApplyUsageRights() {
        $rootScope.$broadcast(batchApplyUsageRightsEvent, {
            data: ctrl.usageRights.data
        });
    }

    function openCollectionTree() {
        ctrl.addCollection = true;

        collections.getCollections().then(collections => {
            ctrl.collections = collections.data.children;
            // this will trigger the remember-scroll-top directive to return
            // users to their previous position on the collections panel
            // once the tree has been rendered
            $timeout(() => {
                $scope.$broadcast('gr:remember-scroll-top:apply');
            });
        }, () => {
            // TODO: More informative error handling
            // TODO: Stop error propagating to global error handler
            ctrl.error = true;
        }).catch(() => ctrl.collectionError = true);

    }

    function addToCollection(collection) {
        collections.addCollectionToImage(ctrl.image, collection);
        //this isn't needed when called from batch apply
        ctrl.addCollection = false;
    }

    function removeImageFromCollection(collection) {
        collections.removeImageFromCollection(collection, ctrl.image);
    }

    function batchApplyCollections() {
        const collectionsOnImage = ctrl.image.data.collections.map(collection =>
                collection.data.path);

        if (collectionsOnImage.length > 0) {
            $rootScope.$broadcast(batchApplyCollectionsEvent, { collections: collectionsOnImage } );
        } else {
            ctrl.confirmDelete = true;

            $timeout(() => {
                ctrl.confirmDelete = false;
            }, 5000);
        }

        ctrl.batchRemoveCollections = () => {
            $rootScope.$broadcast(batchRemoveCollectionsEvent);
        };

    }

    function getCollectionStyle(collection) {
        return collection.data.cssColour && `background-color: ${collection.data.cssColour}`;
    }

    function undelete() {
        const imageId = ctrl.image.data.id;
        mediaApi.undelete(imageId).then(
            ctrl.canUndelete = ctrl.isDeleted = false
        );
    }
}]);


imageEditor.directive('uiImageEditor', [function() {
    return {
        restrict: 'E',
        controller: 'ImageEditorCtrl',
        controllerAs: 'ctrl',
        bindToController: true,
        template: template,
        transclude: true,
        scope: {
            image: '=',
            // FIXME: we only need these to pass them through to `required-metadata-editor`
            withBatch: '='
        }
    };
}]);
